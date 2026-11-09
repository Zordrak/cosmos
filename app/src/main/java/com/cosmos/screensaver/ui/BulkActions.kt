package com.cosmos.screensaver.ui

import android.widget.Toast
import com.cosmos.screensaver.R
import com.cosmos.screensaver.rights.AssetProvider
import com.cosmos.screensaver.settings.ImageSelection
import com.cosmos.screensaver.sync.LibraryStorage
import com.cosmos.screensaver.sync.TrashBin

/**
 * Tidy up (remove saved pictures the current choices would not show) and Clear (remove every
 * non-favourite picture), as one flow: live review, Cancel-first confirmation, undoable commit.
 * After a successful change the shared Undo row takes focus on whichever page started it.
 */
internal class BulkActions(private val env: PageEnv) {
    private val activity get() = env.activity
    private var focusUndoWhenShown = false

    enum class Action { TIDY, CLEAR }

    fun reset() {
        focusUndoWhenShown = false
    }

    /** Why [action] is unavailable right now, or null when it can be used. */
    fun reason(action: Action): String? {
        val snap = env.snapshot
        val composition = snap.composition
        return when {
            snap.unreadable -> activity.getString(R.string.home_unreadable)
            !snap.loaded -> activity.getString(R.string.bulk_loading)
            snap.pending != null -> activity.getString(R.string.use_size_busy)
            action == Action.TIDY && composition.keptPictures == 0 -> activity.getString(R.string.tidy_nothing)
            action == Action.CLEAR && composition.clearablePictures == 0 -> activity.getString(R.string.clear_nothing)
            else -> null
        }
    }

    /** Call after each snapshot refresh so the Undo row is focused once it appears. */
    fun onSnapshot(ui: SettingsUi) {
        if (focusUndoWhenShown && env.snapshot.pending != null) {
            focusUndoWhenShown = false
            ui.rows.firstOrNull { it.id == UndoNotice.ROW_ID }?.view?.requestFocus()
        }
    }

    fun request(action: Action) {
        val appContext = activity.applicationContext
        val selection = env.settings.selection
        val providers = env.settings.enabledProviders
        LibraryStorage.post {
            val trash = TrashBin.of(appContext)
            val chooser = chooser(action, selection, providers)
            val preview = trash.preview(chooser)
            activity.runOnUiThread { review(action, trash, chooser, preview) }
        }
    }

    private fun chooser(
        action: Action,
        selection: ImageSelection,
        providers: Set<AssetProvider>
    ) = when (action) {
        Action.TIDY -> TrashBin.Chooser.notShown(selection, providers)
        Action.CLEAR -> TrashBin.Chooser.everything()
    }

    private fun kind(action: Action) = if (action == Action.TIDY) TrashBin.Kind.TIDY else TrashBin.Kind.CLEAR

    private fun review(action: Action, trash: TrashBin, chooser: TrashBin.Chooser, preview: TrashBin.Preview) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (preview.unreadable) {
            Toast.makeText(activity, BulkCommit.UNREADABLE, Toast.LENGTH_LONG).show()
            return
        }
        if (preview.pictures == 0) {
            Toast.makeText(
                activity,
                activity.getString(if (action == Action.TIDY) R.string.tidy_nothing else R.string.clear_nothing),
                Toast.LENGTH_LONG
            ).show()
            env.libraryChanged()
            return
        }
        val res = activity.resources
        val size = ByteSizes.format(preview.bytes)
        val parts = mutableListOf(
            if (action == Action.TIDY) {
                res.getQuantityString(R.plurals.tidy_confirm_message, preview.pictures, preview.pictures, size)
            } else {
                res.getQuantityString(R.plurals.clear_confirm_message, preview.pictures, preview.pictures, size)
            }
        )
        val favourites = env.snapshot.favouritePictures
        if (favourites > 0) parts += res.getQuantityString(R.plurals.space_confirm_favourites, favourites, favourites)
        parts += activity.getString(
            if (action == Action.TIDY) R.string.tidy_confirm_future else R.string.clear_confirm_future
        )
        val undoSeconds = (TrashBin.UNDO_WINDOW_MILLIS / 1000L).toInt()
        parts += res.getQuantityString(R.plurals.bulk_confirm_undo, undoSeconds, undoSeconds)
        ConfirmDialog.show(
            activity,
            activity.getString(if (action == Action.TIDY) R.string.tidy_confirm_title else R.string.clear_confirm_title),
            parts.joinToString("\n\n"),
            res.getQuantityString(R.plurals.space_confirm_button, preview.pictures, preview.pictures)
        ) { commit(action, trash, chooser, preview) }
    }

    private fun commit(action: Action, trash: TrashBin, chooser: TrashBin.Chooser, expected: TrashBin.Preview) {
        LibraryStorage.post {
            val outcome = BulkCommit.commit(trash, kind(action), chooser, expected)
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                when (outcome) {
                    is BulkCommit.Outcome.Undoable -> focusUndoWhenShown = true
                    BulkCommit.Outcome.Nothing -> Toast.makeText(
                        activity,
                        activity.getString(if (action == Action.TIDY) R.string.tidy_nothing else R.string.clear_nothing),
                        Toast.LENGTH_LONG
                    ).show()
                    is BulkCommit.Outcome.Refused -> Toast.makeText(activity, outcome.message, Toast.LENGTH_LONG).show()
                    is BulkCommit.Outcome.Changed -> {
                        Toast.makeText(activity, R.string.bulk_changed, Toast.LENGTH_LONG).show()
                        review(action, trash, chooser, outcome.preview)
                    }
                }
                env.libraryChanged()
            }
        }
    }
}
