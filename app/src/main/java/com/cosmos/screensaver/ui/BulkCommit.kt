package com.cosmos.screensaver.ui

import com.cosmos.screensaver.sync.TrashBin

/**
 * Tidy up and Clear: a review of what would be removed, then an undoable commit through the Undo
 * engine. Neither touches settings, the blocklist, history or favourites, and the numbers the owner
 * confirmed are re-checked against the live library before anything is removed.
 */
internal object BulkCommit {
    sealed interface Outcome {
        /** Pictures were set aside; they can be put back for a short time. */
        data class Undoable(val pending: TrashBin.Pending) : Outcome

        /** There was nothing to remove. */
        data object Nothing : Outcome

        /** Nothing was changed. [message] says why in words fit for the owner. */
        data class Refused(val message: String) : Outcome

        /** What the change would remove is no longer what was shown; ask again with [preview]. */
        data class Changed(val preview: TrashBin.Preview) : Outcome
    }

    fun commit(trash: TrashBin, kind: TrashBin.Kind, chooser: TrashBin.Chooser, expected: TrashBin.Preview): Outcome {
        val live = trash.preview(chooser)
        if (live.unreadable) return Outcome.Refused(UNREADABLE)
        if (live.pictures != expected.pictures || live.bytes != expected.bytes) return Outcome.Changed(live)
        return when (val result = trash.begin(kind, chooser)) {
            is TrashBin.BeginResult.Started -> Outcome.Undoable(result.pending)
            is TrashBin.BeginResult.NothingToRemove -> Outcome.Nothing
            is TrashBin.BeginResult.Busy -> Outcome.Refused(result.message)
            TrashBin.BeginResult.Unreadable -> Outcome.Refused(UNREADABLE)
            is TrashBin.BeginResult.Failed -> Outcome.Refused(result.reason)
        }
    }

    const val UNREADABLE = "Cosmos can't read its saved pictures right now, so nothing was changed."
}
