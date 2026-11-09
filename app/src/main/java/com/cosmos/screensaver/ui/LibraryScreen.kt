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
 * The saved pictures browser (and, in [blockedMode], the blocked pictures list). The list is on
 * the left; the preview, details and actions are on the right. The list is read on the storage
 * thread and published here; selection and every command use a picture's identity, never a list
 * position, so a list that changes underneath can never aim a command at the wrong picture.
 */
internal class LibraryScreen(private val activity: Activity, private val blockedMode: Boolean) {
    private sealed class Item(val id: String) {
        class Picture(val row: LibraryRow) : Item(row.id)
        class BlockedLink(val count: Int) : Item(LINK_ID)
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
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (items[position] is Item.BlockedLink) 1 else 0
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
        activity.setContentView(buildLayout())
        thumbs.onLoaded = { adapter.notifyDataSetChanged() }
        previews.onLoaded = { key -> if (key == currentPreviewKey()) showPreview() }
        showSelection()
        list.requestFocus()
    }

    fun onResume() {
        resumed = true
        reload()
        if (!blockedMode) handler.post(statusPoller)
    }

    fun onPause() {
        resumed = false
        handler.removeCallbacks(statusPoller)
        handler.removeCallbacks(previewRunnable)
    }

    fun onSaveInstanceState(outState: Bundle) {
        selectedId?.let { outState.putString(STATE_SELECTED, it) }
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
            addView(ui.text(activity.getString(if (blockedMode) R.string.library_title_blocked else R.string.library_title_saved), SettingsType.PAGE_TITLE, Color.WHITE, bold = true))
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

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SettingsColors.BACKGROUND)
            setPadding(ui.dp(56), ui.dp(40), ui.dp(40), ui.dp(32))
            addView(header)
            if (!blockedMode) addView(buildStatusPanel())
            addView(columns, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = ui.dp(16) })
        }
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
        val notice = ui.text(" ", SettingsType.SUMMARY, SettingsColors.SECONDARY)
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
            if (blockedMode) visibility = View.GONE
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
            if (!blockedMode) addView(previewFrame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(detailScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, if (blockedMode) 1f else 1.1f).apply { topMargin = ui.dp(12) })
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
                thumb.visibility = View.GONE
                title.text = item.record.title
                subtitle.text = activity.getString(
                    R.string.library_blocked_subtitle,
                    item.record.sourceLabel,
                    dateFormat.format(Date(item.record.blockedAtEpochMillis))
                )
            }
            is Item.BlockedLink -> {
                thumb.visibility = View.GONE
                title.text = activity.getString(R.string.library_blocked_link, item.count)
                subtitle.text = ""
                subtitle.visibility = View.GONE
            }
        }
        if (item !is Item.BlockedLink) subtitle.visibility = View.VISIBLE
        row.background = if (!listFocused && position == list.selectedItemPosition) dimmedSelection() else null
        return row
    }

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
        updateSummary(result)
        showSelection()
    }

    private fun buildItems(result: Loaded): List<Item> = when {
        blockedMode -> result.blocked.map { Item.Blocked(it) }
        else -> result.rows.map { Item.Picture(it) } +
            (if (result.blocked.isNotEmpty()) listOf(Item.BlockedLink(result.blocked.size)) else emptyList())
    }

    private fun updateSummary(result: Loaded) {
        summary.text = when {
            blockedMode || result.unreadable -> ""
            else -> activity.resources.getQuantityString(
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

    private fun currentPreviewKey(): String? = (currentItem() as? Item.Picture)?.row?.imageKey?.plus(":p")

    private fun activateItem(position: Int) {
        when (items.getOrNull(position)) {
            is Item.BlockedLink -> activity.startActivity(com.cosmos.screensaver.LibraryActivity.intent(activity, blocked = true))
            is Item.Picture, is Item.Blocked -> firstVisibleButton()?.requestFocus()
            null -> Unit
        }
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
        val hasDetails = item is Item.Picture || item is Item.Blocked
        detailScroll.visibility = if (hasDetails) View.VISIBLE else View.GONE
        buttonRow.visibility = if (hasDetails) View.VISIBLE else View.GONE
        if (!blockedMode) previewFrame.visibility = if (item is Item.Picture) View.VISIBLE else View.GONE
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
            is Item.Blocked -> setDetails(LibraryDetails.blocked(item.record, formatDate))
            else -> Unit
        }
        placeholder.visibility = if (hasDetails) View.GONE else View.VISIBLE
        placeholder.text = when {
            item is Item.BlockedLink -> activity.getString(R.string.library_blocked_hint)
            result == null -> activity.getString(R.string.library_loading)
            result.unreadable -> activity.getString(R.string.library_unreadable)
            blockedMode -> activity.getString(R.string.library_blocked_empty)
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
        val item = currentItem() as? Item.Picture ?: return
        val directory = loaded?.imagesDirectory ?: return
        if (item.row.id !in (loaded?.missing ?: emptySet())) {
            previews.request(item.row.imageKey + ":p") { decode(File(directory, item.row.fileName), PREVIEW_EDGE) }
        }
        showPreview()
    }

    private fun showPreview() {
        val item = currentItem() as? Item.Picture ?: return
        val bitmap = previews.cached(item.row.imageKey + ":p")
        val unavailable = item.row.id in (loaded?.missing ?: emptySet()) || previews.hasFailed(item.row.imageKey + ":p")
        previewImage.setImageBitmap(bitmap)
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
        ConfirmDialog.show(
            activity,
            activity.getString(R.string.library_block_title),
            activity.getString(R.string.library_block_message, row.title),
            activity.getString(R.string.library_block)
        ) {
            run(R.string.library_toast_blocked, refocusList = true) {
                LibraryMaintenance.blockPicture(RefreshCoordinator.store(appContext), LibraryStorage.userState(appContext), id)
            }
        }
    }

    private fun onUnblock() {
        val record = (currentItem() as? Item.Blocked)?.record ?: return
        val recordId = record.id
        run(R.string.library_toast_unblocked, refocusList = true) {
            if (LibraryMaintenance.unblock(RefreshCoordinator.store(appContext), LibraryStorage.userState(appContext), recordId)) {
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
            else -> notice.text = " "
        }

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
        const val LINK_ID = "link"
        const val STATE_SELECTED = "selected"
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
