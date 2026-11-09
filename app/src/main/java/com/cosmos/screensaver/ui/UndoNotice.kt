package com.cosmos.screensaver.ui

import android.widget.Toast
import com.cosmos.screensaver.R
import com.cosmos.screensaver.sync.LibraryStorage
import com.cosmos.screensaver.sync.TrashBin

/**
 * The one Undo row every page shows while a bulk change can still be put back. It reads the state
 * from the library snapshot (which the screen refreshes about once a second while an Undo is
 * pending, finalising it when its time is up) and never touches the trash itself except through
 * [TrashBin.undo].
 */
internal object UndoNotice {
    const val ROW_ID = "undo"

    fun row(ui: SettingsUi, env: PageEnv): SettingsRow {
        val activity = env.activity
        return ui.action(
            ROW_ID, activity.getString(R.string.undo_title),
            summary = { summary(env) },
            value = { activity.getString(R.string.undo_button) },
            visible = { env.snapshot.pending != null }
        ) {
            val appContext = activity.applicationContext
            LibraryStorage.post {
                val result = TrashBin.of(appContext).undo()
                activity.runOnUiThread {
                    Toast.makeText(activity, resultMessage(env, result), Toast.LENGTH_LONG).show()
                    env.libraryChanged()
                }
            }
        }
    }

    fun summary(env: PageEnv): String {
        val activity = env.activity
        val pending = env.snapshot.pending ?: return ""
        val seconds = ((pending.remainingMillis + 999L) / 1000L).toInt()
        val what = activity.resources.getQuantityString(
            R.plurals.undo_removed, pending.pictures, pending.pictures, ByteSizes.format(pending.bytes)
        )
        val tail = activity.getString(
            if (pending.restoresLimit) R.string.undo_tail_with_size else R.string.undo_tail, seconds
        )
        return "$what $tail"
    }

    private fun resultMessage(env: PageEnv, result: TrashBin.UndoResult): String {
        val res = env.activity.resources
        return when (result.status) {
            TrashBin.UndoResult.Status.DONE ->
                res.getQuantityString(R.plurals.undo_done, result.restoredPictures, result.restoredPictures)
            TrashBin.UndoResult.Status.PARTIAL ->
                res.getString(R.string.undo_partial, result.restoredPictures, result.failedPictures + result.skippedPictures)
            TrashBin.UndoResult.Status.EXPIRED -> res.getString(R.string.undo_expired)
            TrashBin.UndoResult.Status.NOTHING_TO_UNDO -> res.getString(R.string.undo_nothing)
            TrashBin.UndoResult.Status.FAILED -> result.message.ifBlank { res.getString(R.string.undo_failed) }
        }
    }
}
