package com.cosmos.screensaver.ui

import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.cosmos.screensaver.R
import com.cosmos.screensaver.settings.CosmosSettings
import com.cosmos.screensaver.sync.CacheBudget
import com.cosmos.screensaver.sync.LibraryStorage
import com.cosmos.screensaver.sync.RefreshCoordinator
import com.cosmos.screensaver.sync.TrashBin

/**
 * "Space for pictures": look at a size, see exactly what it means on a bar of this TV's storage, and
 * only then apply it. Moving through the sizes never changes anything; "Use this size" does, after a
 * specific confirmation when it would remove pictures, and the result can be undone for a short time.
 */
internal class SpacePage(private val env: PageEnv) {
    private val activity get() = env.activity
    private val session = SizeSession(env.settings.cacheLimitBytes)
    private var focusUndoWhenShown = false

    private fun s(id: Int) = activity.getString(id)

    fun label(limit: Long): String = limitLabel(activity, limit)

    companion object {
        fun limitLabel(activity: android.app.Activity, limit: Long): String =
            if (limit == 0L) activity.getString(R.string.value_automatic) else ByteSizes.format(limit)
    }

    private fun detail(limit: Long): String =
        if (limit == 0L) activity.getString(R.string.space_value_auto, ByteSizes.format(CacheBudget.AUTO_LIMIT_BYTES))
        else ByteSizes.format(limit)

    fun build(left: LinearLayout): PageContent {
        val ui = SettingsUi(activity)
        left.addView(ui.text(s(R.string.row_space), SettingsType.PAGE_TITLE + 6f, Color.WHITE, bold = true))
        val current = ui.text("", SettingsType.PAGE_TITLE, SettingsColors.ACCENT, bold = true).apply { setPadding(0, ui.dp(12), 0, 0) }
        val trying = ui.text("", SettingsType.LEFT_PANE, SettingsColors.WARNING).apply {
            setPadding(0, ui.dp(4), 0, 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val bar = StorageBarView(activity)
        val legend = StorageLegendView(activity, ByteSizes::format)
        val barHint = ui.text(s(R.string.space_left_hint), 14f, SettingsColors.MUTED).apply { setPadding(0, ui.dp(4), 0, ui.dp(8)) }
        val advice = ui.text("", SettingsType.LEFT_PANE, SettingsColors.SECONDARY).apply { setPadding(0, ui.dp(8), 0, 0) }
        val notes = ui.text("", 15f, SettingsColors.WARNING).apply { setPadding(0, ui.dp(8), 0, 0) }
        left.addView(current)
        left.addView(trying)
        left.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ui.dp(16)
        })
        left.addView(barHint)
        left.addView(legend)
        left.addView(advice)
        left.addView(notes)

        UndoNotice.row(ui, env)
        ui.choice(
            "size", s(R.string.row_size),
            options = {
                CosmosSettings.CACHE_LIMIT_CHOICES.map {
                    PickerOption(it, label(it), recommended = it == 0L)
                }
            },
            current = { session.candidate },
            onChosen = { session.propose(it) },
            summary = {
                if (it == 0L) activity.getString(R.string.size_summary_auto, ByteSizes.format(CacheBudget.AUTO_LIMIT_BYTES))
                else activity.getString(R.string.size_summary_fixed, ByteSizes.format(it))
            },
            valueLabel = ::label
        )
        ui.action(
            "use_size", s(R.string.row_use_size),
            summary = { activity.getString(R.string.use_size_summary, label(session.candidate)) },
            enabledReason = {
                when {
                    !session.hasProposal -> s(R.string.use_size_reason)
                    env.snapshot.pending != null -> s(R.string.use_size_busy)
                    else -> null
                }
            }
        ) { requestCommit(ui) }
        ui.onChanged = { updateLeft(ui, current, trying, bar, legend, advice, notes) }

        return PageContent(ui.rows) { updateLeft(ui, current, trying, bar, legend, advice, notes) }
    }

    private fun updateLeft(
        ui: SettingsUi, current: TextView, trying: TextView, bar: StorageBarView,
        legend: StorageLegendView, advice: TextView, notes: TextView
    ) {
        if (env.settings.cacheLimitBytes != session.committed) session.resync(env.settings.cacheLimitBytes)
        val snap = env.snapshot
        current.text = detail(session.committed)
        trying.text = if (session.hasProposal) {
            activity.getString(R.string.space_trying, detail(session.candidate), label(session.committed))
        } else ""
        val layout = snap.barLayout(session.candidate)
        bar.show(layout)
        legend.show(layout)

        val lines = mutableListOf<String>()
        when {
            !snap.loaded -> advice.text = ""
            snap.unreadable -> {
                advice.text = ""
                lines += s(R.string.home_unreadable)
            }
            else -> {
                val info = SpaceAdvisor.advise(layout, snap.pictures, snap.bytes, snap.favouriteBytes, snap.trashBytes)
                val res = activity.resources
                advice.text = when {
                    !layout.known -> ""
                    info.estimated -> res.getQuantityString(R.plurals.space_room_estimate, info.capacityPictures, info.capacityPictures)
                    else -> res.getQuantityString(
                        R.plurals.space_room_exact, info.capacityPictures, info.capacityPictures,
                        ByteSizes.format(info.averageBytes)
                    )
                }
                val reserve = ByteSizes.format(layout.budget.reserveBytes)
                info.notes.forEach {
                    lines += when (it) {
                        SpaceNote.MEASUREMENT_UNAVAILABLE -> s(R.string.space_note_unavailable)
                        SpaceNote.CONSTRAINED_BY_FREE_SPACE ->
                            activity.getString(R.string.space_note_constrained, ByteSizes.format(layout.budget.budgetBytes), reserve)
                        SpaceNote.FAVOURITES_ABOVE_TARGET -> s(R.string.space_note_favourites)
                        SpaceNote.UNDO_PENDING -> s(R.string.space_note_undo)
                    }
                }
                if (layout.reserveShortfallBytes > 0L) lines += activity.getString(R.string.space_note_shortfall, reserve)
            }
        }
        notes.text = lines.joinToString("\n")
        if (focusUndoWhenShown && snap.pending != null) {
            focusUndoWhenShown = false
            ui.rows.firstOrNull { it.id == UndoNotice.ROW_ID }?.view?.requestFocus()
        }
    }

    private fun requestCommit(ui: SettingsUi) {
        if (!session.hasProposal) return
        val candidate = session.candidate
        val appContext = activity.applicationContext
        val selection = env.settings.selection
        val providers = env.settings.enabledProviders
        LibraryStorage.post {
            val trash = TrashBin.of(appContext)
            val chooser = SizeCommit.chooser(
                measure = { RefreshCoordinator.measure(appContext) },
                trashBytes = { trash.bytesOnDisk() },
                candidate = candidate,
                selection = selection,
                providers = providers
            )
            val preview = trash.preview(chooser)
            activity.runOnUiThread { review(ui, trash, chooser, candidate, preview) }
        }
    }

    private fun review(ui: SettingsUi, trash: TrashBin, chooser: TrashBin.Chooser, candidate: Long, preview: TrashBin.Preview) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (preview.unreadable) {
            Toast.makeText(activity, SizeCommit.UNREADABLE, Toast.LENGTH_LONG).show()
            return
        }
        if (preview.pictures == 0) {
            commit(ui, trash, chooser, candidate, preview)
            return
        }
        val res = activity.resources
        val parts = mutableListOf(
            res.getQuantityString(
                R.plurals.space_confirm_message, preview.pictures,
                label(candidate), preview.pictures, ByteSizes.format(preview.bytes)
            )
        )
        val favourites = env.snapshot.favouritePictures
        if (favourites > 0) parts += res.getQuantityString(R.plurals.space_confirm_favourites, favourites, favourites)
        val undoSeconds = (TrashBin.UNDO_WINDOW_MILLIS / 1000L).toInt()
        parts += res.getQuantityString(R.plurals.space_confirm_undo, undoSeconds, undoSeconds)
        ConfirmDialog.show(
            activity,
            activity.getString(R.string.space_confirm_title, label(candidate)),
            parts.joinToString("\n\n"),
            res.getQuantityString(R.plurals.space_confirm_button, preview.pictures, preview.pictures)
        ) { commit(ui, trash, chooser, candidate, preview) }
    }

    private fun commit(ui: SettingsUi, trash: TrashBin, chooser: TrashBin.Chooser, candidate: Long, expected: TrashBin.Preview) {
        LibraryStorage.post {
            val outcome = SizeCommit.commit(trash, chooser, candidate, expected)
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                when (outcome) {
                    SizeCommit.Outcome.Applied -> {
                        session.applied(candidate)
                        Toast.makeText(activity, activity.getString(R.string.space_applied, label(candidate)), Toast.LENGTH_LONG).show()
                    }
                    is SizeCommit.Outcome.Undoable -> {
                        session.applied(candidate)
                        focusUndoWhenShown = true
                    }
                    is SizeCommit.Outcome.Refused -> Toast.makeText(activity, outcome.message, Toast.LENGTH_LONG).show()
                    is SizeCommit.Outcome.Changed -> {
                        Toast.makeText(activity, R.string.space_changed, Toast.LENGTH_LONG).show()
                        review(ui, trash, chooser, candidate, outcome.preview)
                    }
                }
                env.libraryChanged()
            }
        }
    }
}
