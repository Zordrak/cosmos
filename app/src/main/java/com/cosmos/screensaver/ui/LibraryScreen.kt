package com.cosmos.screensaver.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.cosmos.screensaver.R
import com.cosmos.screensaver.catalogue.BlockedRecord
import com.cosmos.screensaver.catalogue.LibraryMaintenance
import com.cosmos.screensaver.catalogue.StoreRead
import com.cosmos.screensaver.settings.CosmosSettings
import com.cosmos.screensaver.settings.SelectionSnapshot
import com.cosmos.screensaver.sync.LibraryStorage
import com.cosmos.screensaver.sync.RefreshCoordinator
import com.cosmos.screensaver.sync.RefreshProgress
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/**
 * The saved pictures browser: one place for every picture-level action. Tabs at the top switch
 * between all saved pictures, favourites and blocked pictures. The list is on the left; the
 * preview, details and actions are on the right. The list is read on the storage thread and
 * published here; selection and every command use a picture's identity, never a list position, so
 * a list that changes underneath can never aim a command at the wrong picture.
 */
internal class LibraryScreen(private val activity: Activity) {
    enum class Filter { ALL, FAVOURITES, BLOCKED }

    private sealed class Item(val id: String) {
        class Picture(val row: LibraryRow) : Item(row.id)
        class Blocked(val record: BlockedRecord) : Item("b:" + record.id)
    }

    private class Loaded(
        val revision: Long,
        val unreadable: Boolean,
        val rows: List<LibraryRow>,
        val blocked: List<BlockedRecord>,
        val missing: Set<String>,
        val imagesDirectory: File?,
        val lastAttemptAtEpochMillis: Long,
        val totalBytes: Long
    )

    private val ui = SettingsUi(activity)
    private val appContext = activity.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)

    private val thumbs = AsyncImageCache<Bitmap>(
        executor = Executors.newSingleThreadExecutor(::decodeThread),
        post = { handler.post(it) },
        sizeOf = { it.byteCount },
        maxBytes = THUMB_CACHE_BYTES
    )
    private val previews = AsyncImageCache<Bitmap>(
        executor = Executors.newSingleThreadExecutor(::decodeThread),
        post = { handler.post(it) },
        sizeOf = { it.byteCount },
        maxBytes = PREVIEW_CACHE_BYTES,
        maxJobs = 2
    )

    private var loaded: Loaded? = null
    private var filter = Filter.ALL
    private var focusListOnLoad = true
    private var items: List<Item> = emptyList()
    private var selectedId: String? = null
    private var requested = 0L
    private var applied = 0L
    private var destroyed = false
    private var listFocused = false
    private var resumed = false
    private var lastRunId = -1L
    private var lastAdmitted = -1
    private var wasActive = false

    private lateinit var list: ListView
    private lateinit var tabs: Map<Filter, TextView>
    private lateinit var summary: TextView
    private lateinit var previewFrame: FrameLayout
    private lateinit var previewImage: ImageView
    private lateinit var previewNote: TextView
    private lateinit var placeholder: TextView
    private lateinit var detailScroll: ScrollView
    private lateinit var detailText: TextView
    private lateinit var buttonRow: LinearLayout
    private lateinit var favouriteButton: TextView
    private lateinit var removeButton: TextView
    private lateinit var blockButton: TextView
    private lateinit var unblockButton: TextView

    private var statusHeadline: TextView? = null
    private var statusBar: ProgressBar? = null
    private var statusDetail: TextView? = null
    private var statusNotice: TextView? = null

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            bindRow(position, convertView)
    }

    private val previewRunnable = Runnable { loadPreview() }
    private val statusPoller = object : Runnable {
        override fun run() {
            updateStatus()
            if (resumed) handler.postDelayed(this, STATUS_MILLIS)
        }
    }

    fun onCreate(savedInstanceState: Bundle?) {
        selectedId = savedInstanceState?.getString(STATE_SELECTED)
        filter = savedInstanceState?.getString(STATE_FILTER)?.let { name -> Filter.entries.firstOrNull { it.name == name } } ?: Filter.ALL
        activity.setContentView(buildLayout())
        thumbs.onLoaded = { adapter.notifyDataSetChanged() }
        previews.onLoaded = { key -> if (key == currentPreviewKey()) showPreview() }
        updateTabs()
        showSelection()
        list.requestFocus()
    }

    fun onResume() {
        resumed = true
        reload()
        handler.post(statusPoller)
    }

    fun onPause() {
        resumed = false
        handler.removeCallbacks(statusPoller)
        handler.removeCallbacks(previewRunnable)
    }

    fun onSaveInstanceState(outState: Bundle) {
        selectedId?.let { outState.putString(STATE_SELECTED, it) }
        outState.putString(STATE_FILTER, filter.name)
    }

    fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        thumbs.close()
        previews.close()
    }

    // ---- layout -------------------------------------------------------------------------------

    private fun buildLayout(): View {
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.text(activity.getString(R.string.library_title_saved), SettingsType.PAGE_TITLE, Color.WHITE, bold = true))
            summary = ui.text("", SettingsType.SUMMARY, SettingsColors.SECONDARY).apply { setPadding(0, ui.dp(4), 0, 0) }
            addView(summary)
        }

        list = ListView(activity).apply {
            id = View.generateViewId()
            divider = null
            selector = ui.rowBackground()
            isVerticalScrollBarEnabled = false
            isFocusable = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            adapter = this@LibraryScreen.adapter
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = select(position)
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
            setOnItemClickListener { _, _, position, _ -> activateItem(position) }
            setOnFocusChangeListener { _, hasFocus ->
                listFocused = hasFocus
                this@LibraryScreen.adapter.notifyDataSetChanged()
            }
        }

        val right = buildRightPane()
        val columns = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(list, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.42f).apply { rightMargin = ui.dp(24) })
            addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.58f))
        }
        val top = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(header, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(buildTabs())
        }

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SettingsColors.BACKGROUND)
            setPadding(ui.dp(56), ui.dp(32), ui.dp(40), ui.dp(28))
            addView(top)
            addView(buildStatusPanel())
            addView(columns, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = ui.dp(12) })
        }
    }

    /** All, Favourites and Blocked. Moving onto a tab shows its pictures; Down returns to the list. */
    private fun buildTabs(): View {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        tabs = Filter.entries.associateWith { tab ->
            button("", Color.WHITE) { list.requestFocus() }.also { view ->
                view.nextFocusDownId = list.id
                view.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) showFilter(tab) }
                row.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = ui.dp(12) })
            }
        }
        return row
    }

    private fun showFilter(next: Filter) {
        if (next == filter) return
        filter = next
        selectedId = null
        updateTabs()
        loaded?.let(::apply)
    }

    private fun updateTabs() {
        if (!::tabs.isInitialized) return
        val result = loaded
        val counts = mapOf(
            Filter.ALL to (result?.rows?.size ?: 0),
            Filter.FAVOURITES to (result?.rows?.count { it.favourite } ?: 0),
            Filter.BLOCKED to (result?.blocked?.size ?: 0)
        )
        tabs.forEach { (tab, view) ->
            view.text = activity.getString(
                when (tab) {
                    Filter.ALL -> R.string.library_tab_all
                    Filter.FAVOURITES -> R.string.library_tab_favourites
                    Filter.BLOCKED -> R.string.library_tab_blocked
                },
                counts.getValue(tab)
            )
            view.setTextColor(if (tab == filter) SettingsColors.ACCENT else SettingsColors.SECONDARY)
            view.paint.isUnderlineText = tab == filter
            view.invalidate()
        }
        list.nextFocusUpId = tabs.getValue(filter).id
    }

    private fun buildStatusPanel(): View {
        val headline = ui.text(" ", SettingsType.ROW_VALUE, Color.WHITE, bold = true)
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = PROGRESS_MAX
            progressTintList = ColorStateList.valueOf(SettingsColors.ACCENT)
            indeterminateTintList = ColorStateList.valueOf(SettingsColors.ACCENT)
            visibility = View.INVISIBLE
        }
        val detail = ui.text(" ", SettingsType.SUMMARY, SettingsColors.SECONDARY)
        val notice = ui.text("", SettingsType.SUMMARY, SettingsColors.SECONDARY).apply { visibility = View.GONE }
        statusHeadline = headline
        statusBar = bar
        statusDetail = detail
        statusNotice = notice
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
            background = GradientDrawable().apply {
                setColor(SettingsColors.PANEL)
                cornerRadius = ui.dp(10).toFloat()
            }
            addView(headline)
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(8)).apply {
                topMargin = ui.dp(6)
                bottomMargin = ui.dp(6)
            })
            addView(detail)
            addView(notice)
        }.let { panel ->
            FrameLayout(activity).apply {
                setPadding(0, ui.dp(16), 0, 0)
                addView(panel)
            }
        }
    }

    private fun buildRightPane(): View {
        previewImage = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = false
        }
        previewNote = ui.text(activity.getString(R.string.library_preview_unavailable), SettingsType.SUMMARY, SettingsColors.MUTED).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        previewFrame = FrameLayout(activity).apply {
            setBackgroundColor(SettingsColors.PANEL)
            addView(previewImage, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(previewNote, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }

        detailText = ui.text("", SettingsType.SUMMARY, SettingsColors.SECONDARY).apply {
            setLineSpacing(0f, 1.1f)
            setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
        }
        detailScroll = ScrollView(activity).apply {
            isFocusable = true
            isVerticalScrollBarEnabled = false
            background = ui.rowBackground()
            addView(detailText)
        }

        favouriteButton = button(activity.getString(R.string.library_favourite), Color.WHITE) { onFavourite() }
        removeButton = button(activity.getString(R.string.library_remove), SettingsColors.WARNING) { onRemove() }
        blockButton = button(activity.getString(R.string.library_block), SettingsColors.WARNING) { onBlock() }
        unblockButton = button(activity.getString(R.string.library_unblock), Color.WHITE) { onUnblock() }
        buttonRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(favouriteButton, removeButton, blockButton, unblockButton).forEach {
                addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = ui.dp(12) })
            }
        }
        listOf(favouriteButton, removeButton, blockButton, unblockButton).forEach { it.nextFocusLeftId = list.id }
        detailScroll.nextFocusLeftId = list.id

        placeholder = ui.text("", SettingsType.ROW_VALUE, SettingsColors.SECONDARY).apply {
            setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
        }

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(placeholder)
            addView(previewFrame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(detailScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.1f).apply { topMargin = ui.dp(12) })
            addView(buttonRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(12) })
        }
    }

    private fun button(label: String, textColor: Int, onClick: () -> Unit) = TextView(activity).apply {
        text = label
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, SettingsType.ROW_VALUE)
        setTextColor(textColor)
        gravity = Gravity.CENTER
        setPadding(ui.dp(24), ui.dp(14), ui.dp(24), ui.dp(14))
        isFocusable = true
        isClickable = true
        id = View.generateViewId()
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                setColor(BUTTON_FOCUSED)
                setStroke(ui.dp(2), SettingsColors.ACCENT)
                cornerRadius = ui.dp(10).toFloat()
            })
            addState(intArrayOf(), GradientDrawable().apply {
                setColor(SettingsColors.PANEL)
                setStroke(ui.dp(2), BUTTON_OUTLINE)
                cornerRadius = ui.dp(10).toFloat()
            })
        }
        setOnClickListener { onClick() }
    }

    private fun bindRow(position: Int, convertView: View?): View {
        val item = items[position]
        val row = (convertView as? LinearLayout) ?: LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8))
            minimumHeight = ui.dp(80)
            val thumb = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(SettingsColors.PANEL) }
            addView(thumb, LinearLayout.LayoutParams(ui.dp(96), ui.dp(64)).apply { rightMargin = ui.dp(14) })
            val texts = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(ui.text("", SettingsType.ROW_TITLE - 2f, Color.WHITE).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                addView(ui.text("", SettingsType.SUMMARY, SettingsColors.SECONDARY).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            }
            addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        val thumb = row.getChildAt(0) as ImageView
        val texts = row.getChildAt(1) as LinearLayout
        val title = texts.getChildAt(0) as TextView
        val subtitle = texts.getChildAt(1) as TextView

        when (item) {
            is Item.Picture -> {
                thumb.visibility = View.VISIBLE
                title.text = if (item.row.favourite) activity.getString(R.string.library_title_favourite, item.row.title) else item.row.title
                subtitle.text = item.row.subtitle
                bindThumbnail(thumb, item.row)
            }
            is Item.Blocked -> {
                thumb.visibility = View.VISIBLE
                title.text = item.record.title
                subtitle.text = activity.getString(
                    R.string.library_blocked_subtitle,
                    item.record.sourceLabel,
                    dateFormat.format(Date(item.record.blockedAtEpochMillis))
                )
                bindBlockedThumbnail(thumb, item.record)
            }
        }
        row.background = if (!listFocused && position == list.selectedItemPosition) dimmedSelection() else null
        return row
    }

    private fun bindBlockedThumbnail(view: ImageView, record: BlockedRecord) {
        val key = blockedKey(record) + ":t"
        val cached = thumbs.cached(key)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageDrawable(null)
        val file = BlockedThumbnails.file(appContext, record.sha256) ?: return
        thumbs.request(key) { if (file.isFile) decode(file, THUMB_EDGE) else null }
    }

    private fun blockedKey(record: BlockedRecord) = "b:${record.id}:${record.sha256}"

    private fun dimmedSelection() = GradientDrawable().apply {
        setColor(DIMMED_SELECTION)
        cornerRadius = ui.dp(10).toFloat()
    }

    private fun bindThumbnail(view: ImageView, row: LibraryRow) {
        val key = row.imageKey + ":t"
        val cached = thumbs.cached(key)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageDrawable(null)
        val directory = loaded?.imagesDirectory ?: return
        if (row.id in (loaded?.missing ?: emptySet())) return
        thumbs.request(key) { decode(File(directory, row.fileName), THUMB_EDGE) }
    }

    // ---- data ---------------------------------------------------------------------------------

    private fun reload() {
        val revision = ++requested
        LibraryStorage.post {
            val result = read(revision)
            handler.post {
                if (destroyed || result.revision <= applied) return@post
                applied = result.revision
                apply(result)
            }
        }
    }

    private fun read(revision: Long): Loaded {
        val store = RefreshCoordinator.store(appContext)
        val read = store.read()
        val state = LibraryStorage.userState(appContext).load()
        if (read !is StoreRead.Ok) {
            return Loaded(revision, true, emptyList(), LibraryRows.blocked(state), emptySet(), null, 0L, 0L)
        }
        val settings = CosmosSettings(appContext)
        val entries = read.catalogue.entries
        val eligible = SelectionSnapshot.build(entries, settings.selection, settings.enabledProviders, state).eligibleIdentities
        val rows = LibraryRows.build(entries, state, eligible)
        val directory = store.imagesDirectory
        val missing = rows.filter { !File(directory, it.fileName).exists() }.map { it.id }.toSet()
        return Loaded(
            revision, false, rows, LibraryRows.blocked(state), missing, directory,
            read.catalogue.lastAttemptAtEpochMillis, SelectionSnapshot.physicalBytes(entries)
        )
    }

    private fun apply(result: Loaded) {
        loaded = result
        val next = buildItems(result)
        val index = LibraryRows.resolveSelection(next.map { it.id }, selectedId, items.map { it.id })
        items = next
        adapter.notifyDataSetChanged()
        if (index >= 0) {
            list.setSelection(index)
            selectedId = items[index].id
        } else {
            selectedId = null
        }
        updateTabs()
        updateSummary(result)
        showSelection()
        // An empty list cannot take focus, so a tab has it until the first pictures arrive.
        if (focusListOnLoad && items.isNotEmpty()) {
            focusListOnLoad = false
            list.requestFocus()
        }
    }

    private fun buildItems(result: Loaded): List<Item> = when (filter) {
        Filter.ALL -> result.rows.map { Item.Picture(it) }
        Filter.FAVOURITES -> result.rows.filter { it.favourite }.map { Item.Picture(it) }
        Filter.BLOCKED -> result.blocked.map { Item.Blocked(it) }
    }

    private fun updateSummary(result: Loaded) {
        summary.text = if (result.unreadable) {
            ""
        } else {
            activity.resources.getQuantityString(
                R.plurals.library_summary, result.rows.size, result.rows.size, ByteSizes.format(result.totalBytes)
            )
        }
    }

    // ---- selection ----------------------------------------------------------------------------

    private fun select(position: Int) {
        selectedId = items.getOrNull(position)?.id
        showSelection()
    }

    private fun currentItem(): Item? = items.firstOrNull { it.id == selectedId }

    private fun currentPreviewKey(): String? = when (val item = currentItem()) {
        is Item.Picture -> item.row.imageKey + ":p"
        is Item.Blocked -> blockedKey(item.record) + ":p"
        null -> null
    }

    private fun activateItem(position: Int) {
        if (items.getOrNull(position) != null) firstVisibleButton()?.requestFocus()
    }

    private fun firstVisibleButton(): View? = listOf(favouriteButton, removeButton, blockButton, unblockButton)
        .firstOrNull { it.visibility == View.VISIBLE }

    private fun showSelection() {
        if (!::list.isInitialized) return
        handler.removeCallbacks(previewRunnable)
        val item = currentItem()
        val result = loaded
        val now = System.currentTimeMillis()
        val formatDate: (Long) -> String = { dateFormat.format(Date(it)) }

        previewImage.setImageDrawable(null)
        previewNote.visibility = View.GONE
        val hasDetails = item != null
        detailScroll.visibility = if (hasDetails) View.VISIBLE else View.GONE
        buttonRow.visibility = if (hasDetails) View.VISIBLE else View.GONE
        previewFrame.visibility = if (hasDetails) View.VISIBLE else View.GONE
        favouriteButton.visibility = if (item is Item.Picture) View.VISIBLE else View.GONE
        removeButton.visibility = if (item is Item.Picture) View.VISIBLE else View.GONE
        blockButton.visibility = if (item is Item.Picture) View.VISIBLE else View.GONE
        unblockButton.visibility = if (item is Item.Blocked) View.VISIBLE else View.GONE

        when (item) {
            is Item.Picture -> {
                val exists = item.row.id !in (result?.missing ?: emptySet())
                setDetails(LibraryDetails.sections(item.row, now, formatDate, exists))
                favouriteButton.text = activity.getString(if (item.row.favourite) R.string.library_unfavourite else R.string.library_favourite)
                handler.postDelayed(previewRunnable, PREVIEW_DELAY_MILLIS)
            }
            is Item.Blocked -> {
                val sections = LibraryDetails.blocked(item.record, formatDate)
                val kept = BlockedThumbnails.file(appContext, item.record.sha256)?.isFile == true
                setDetails(
                    if (kept) {
                        listOf(sections.first().let { it.copy(lines = listOf(activity.getString(R.string.library_blocked_preview_note)) + it.lines) }) + sections.drop(1)
                    } else {
                        sections
                    }
                )
                handler.postDelayed(previewRunnable, PREVIEW_DELAY_MILLIS)
            }
            null -> Unit
        }
        placeholder.visibility = if (hasDetails) View.GONE else View.VISIBLE
        placeholder.text = when {
            result == null -> activity.getString(R.string.library_loading)
            result.unreadable -> activity.getString(R.string.library_unreadable)
            filter == Filter.FAVOURITES -> activity.getString(R.string.library_favourites_empty)
            filter == Filter.BLOCKED -> activity.getString(R.string.library_blocked_empty)
            else -> activity.getString(R.string.library_empty)
        }
        list.nextFocusRightId = firstVisibleButton()?.id ?: list.id
    }

    private fun setDetails(sections: List<DetailSection>) {
        val text = SpannableStringBuilder()
        sections.forEachIndexed { index, section ->
            if (index > 0) text.append("\n\n")
            val start = text.length
            text.append(section.heading)
            text.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(ForegroundColorSpan(Color.WHITE), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            section.lines.forEach { text.append('\n').append(it) }
        }
        detailText.text = text
        detailScroll.scrollTo(0, 0)
    }

    private fun loadPreview() {
        when (val item = currentItem()) {
            is Item.Picture -> {
                val directory = loaded?.imagesDirectory ?: return
                if (item.row.id !in (loaded?.missing ?: emptySet())) {
                    previews.request(item.row.imageKey + ":p") { decode(File(directory, item.row.fileName), PREVIEW_EDGE) }
                }
            }
            is Item.Blocked -> {
                val file = BlockedThumbnails.file(appContext, item.record.sha256)
                if (file != null) previews.request(blockedKey(item.record) + ":p") { if (file.isFile) decode(file, PREVIEW_EDGE) else null }
            }
            null -> return
        }
        showPreview()
    }

    private fun showPreview() {
        val key = currentPreviewKey() ?: return
        val bitmap = previews.cached(key)
        val (unavailable, note) = when (val item = currentItem()) {
            is Item.Picture -> (item.row.id in (loaded?.missing ?: emptySet()) || previews.hasFailed(key)) to R.string.library_preview_unavailable
            is Item.Blocked -> (BlockedThumbnails.file(appContext, item.record.sha256)?.isFile != true || previews.hasFailed(key)) to
                R.string.library_blocked_no_preview
            null -> return
        }
        previewImage.setImageBitmap(bitmap)
        previewNote.text = activity.getString(note)
        previewNote.visibility = if (bitmap == null && unavailable) View.VISIBLE else View.GONE
    }

    // ---- commands -----------------------------------------------------------------------------

    private fun onFavourite() {
        val row = (currentItem() as? Item.Picture)?.row ?: return
        val hash = row.aliases.firstOrNull { it.sha256.isNotBlank() }?.sha256
        if (hash == null) {
            toast(R.string.library_toast_no_checksum)
            return
        }
        val on = !row.favourite
        run(if (on) R.string.library_toast_favourited else R.string.library_toast_unfavourited, refocusList = false) {
            if (LibraryStorage.userState(appContext).setFavourite(hash, on)) LibraryMaintenance.Action.DONE else LibraryMaintenance.Action.FAILED
        }
    }

    private fun onRemove() {
        val row = (currentItem() as? Item.Picture)?.row ?: return
        val id = row.id
        ConfirmDialog.show(
            activity,
            activity.getString(R.string.library_remove_title),
            activity.getString(if (row.favourite) R.string.library_remove_message_favourite else R.string.library_remove_message, row.title),
            activity.getString(R.string.library_remove)
        ) {
            run(R.string.library_toast_removed, refocusList = true) {
                LibraryMaintenance.removePicture(RefreshCoordinator.store(appContext), LibraryStorage.userState(appContext), id)
            }
        }
    }

    private fun onBlock() {
        val row = (currentItem() as? Item.Picture)?.row ?: return
        val id = row.id
        val hash = row.aliases.firstOrNull { it.sha256.isNotBlank() }?.sha256
        val source = loaded?.imagesDirectory?.let { File(it, row.fileName) }
        ConfirmDialog.show(
            activity,
            activity.getString(R.string.library_block_title),
            activity.getString(R.string.library_block_message, row.title),
            activity.getString(R.string.library_block)
        ) {
            run(R.string.library_toast_blocked, refocusList = true) {
                if (source != null && source.isFile) BlockedThumbnails.save(appContext, source, hash)
                LibraryMaintenance.blockPicture(RefreshCoordinator.store(appContext), LibraryStorage.userState(appContext), id).also {
                    if (it != LibraryMaintenance.Action.DONE) BlockedThumbnails.delete(appContext, hash)
                }
            }
        }
    }

    private fun onUnblock() {
        val record = (currentItem() as? Item.Blocked)?.record ?: return
        val recordId = record.id
        run(R.string.library_toast_unblocked, refocusList = true) {
            val userState = LibraryStorage.userState(appContext)
            if (LibraryMaintenance.unblock(RefreshCoordinator.store(appContext), userState, recordId)) {
                BlockedThumbnails.keepOnly(appContext, userState.load().blocked.mapNotNull { it.sha256 }.toSet())
                LibraryMaintenance.Action.DONE
            } else {
                LibraryMaintenance.Action.GONE
            }
        }
    }

    private fun run(successMessage: Int, refocusList: Boolean, work: () -> LibraryMaintenance.Action) {
        LibraryStorage.post {
            val action = work()
            handler.post {
                if (destroyed) return@post
                toast(
                    when (action) {
                        LibraryMaintenance.Action.DONE -> successMessage
                        LibraryMaintenance.Action.GONE -> R.string.library_toast_gone
                        LibraryMaintenance.Action.UNREADABLE, LibraryMaintenance.Action.FAILED -> R.string.library_toast_failed
                    }
                )
                reload()
                if (refocusList) list.requestFocus()
            }
        }
    }

    private fun toast(message: Int) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()

    // ---- status -------------------------------------------------------------------------------

    private fun updateStatus() {
        val headline = statusHeadline ?: return
        val bar = statusBar ?: return
        val detail = statusDetail ?: return
        val notice = statusNotice ?: return
        val now = System.currentTimeMillis()
        val snapshot = RefreshProgress.saving.snapshot()
        val model = LibraryStatus.saving(snapshot, loaded?.lastAttemptAtEpochMillis ?: 0L, now)
        val text = LibraryStatus.describe(model)
        headline.text = text.headline
        detail.text = listOf(text.detail, text.runLine).filter { it.isNotEmpty() }.joinToString("   ").ifEmpty { " " }

        bar.visibility = if (model.busy) View.VISIBLE else View.INVISIBLE
        val fraction = model.fraction
        if (fraction == null) {
            bar.isIndeterminate = true
        } else {
            bar.isIndeterminate = false
            bar.progress = (fraction * PROGRESS_MAX).toInt()
        }

        val streaming = LibraryStatus.streaming(RefreshProgress.streaming.snapshot(), now)
        val streamText = streaming?.let { LibraryStatus.describe(it, prefix = "Online playback \u00B7 ") }
        when {
            text.problem.isNotEmpty() -> {
                notice.setTextColor(SettingsColors.WARNING)
                notice.text = text.problem
            }
            streamText != null -> {
                notice.setTextColor(SettingsColors.SECONDARY)
                notice.text = listOf(streamText.headline, streamText.detail).filter { it.isNotEmpty() }.joinToString(" \u00B7 ")
            }
            else -> notice.text = ""
        }
        notice.visibility = if (notice.text.isEmpty()) View.GONE else View.VISIBLE

        val changed = snapshot.runId != lastRunId || snapshot.admitted != lastAdmitted || (wasActive && !snapshot.active)
        val firstPoll = lastRunId == -1L
        lastRunId = snapshot.runId
        lastAdmitted = snapshot.admitted
        wasActive = snapshot.active
        if (changed && !firstPoll) reload()
    }

    private fun decode(file: File, longEdge: Int): Bitmap? {
        val path = file.absolutePath
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = LibraryFormat.thumbnailSampleSize(bounds.outWidth, bounds.outHeight, longEdge)
        }
        return BitmapFactory.decodeFile(path, options)
    }

    private fun decodeThread(runnable: Runnable) = Thread(runnable, "cosmos-library-images").apply {
        isDaemon = true
        priority = Thread.NORM_PRIORITY - 1
    }

    private companion object {
        const val STATE_SELECTED = "selected"
        const val STATE_FILTER = "filter"
        const val STATUS_MILLIS = 500L
        const val PREVIEW_DELAY_MILLIS = 150L
        const val PROGRESS_MAX = 1000
        const val THUMB_EDGE = 192
        const val PREVIEW_EDGE = 960
        const val THUMB_CACHE_BYTES = 8 * 1024 * 1024
        const val PREVIEW_CACHE_BYTES = 6 * 1024 * 1024
        const val DIMMED_SELECTION = 0x1AFFFFFF
        const val BUTTON_FOCUSED = 0xFF22304F.toInt()
        const val BUTTON_OUTLINE = 0x33FFFFFF
    }
}
