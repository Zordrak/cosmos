package com.cosmos.screensaver.ui

import com.cosmos.screensaver.rights.AssetProvider
import com.cosmos.screensaver.settings.CosmosSettings
import com.cosmos.screensaver.settings.ImageSelection
import com.cosmos.screensaver.settings.SelectionSnapshot
import com.cosmos.screensaver.sync.CacheBudget
import com.cosmos.screensaver.sync.StorageMeasurement
import com.cosmos.screensaver.sync.TrashBin

/**
 * The size being looked at versus the size actually in force. Moving through the choices only
 * changes [candidate]: nothing is saved or removed until the owner commits it, and [discard] (Back)
 * forgets the proposal. This object has no storage access, so previewing cannot have side effects.
 */
internal class SizeSession(
    committed: Long,
    private val choices: List<Long> = CosmosSettings.CACHE_LIMIT_CHOICES
) {
    var committed: Long = committed
        private set
    var candidate: Long = committed
        private set

    val hasProposal: Boolean get() = candidate != committed

    /** Moves the proposal one choice in [direction]; false when there is nowhere to go. */
    fun step(direction: Int): Boolean {
        val next = PickerModel.cycle(choices, candidate, direction) ?: return false
        candidate = next
        return true
    }

    /** Proposes [value]; false (and no change) when it is not one of the offered sizes. */
    fun propose(value: Long): Boolean {
        if (value !in choices) return false
        candidate = value
        return true
    }

    fun discard() {
        candidate = committed
    }

    /** The proposal is now the size in force. */
    fun applied(value: Long) {
        committed = value
        candidate = value
    }

    /** The size in force was changed elsewhere (for example by Undo); drop any stale proposal. */
    fun resync(value: Long) {
        committed = value
        candidate = value
    }
}

/** Turning a proposed size into a change: a review of what it would remove, then an undoable commit. */
internal object SizeCommit {
    sealed interface Outcome {
        /** The new size was saved and nothing needed removing. */
        data object Applied : Outcome

        /** The new size was saved and pictures were set aside; they can be put back for a short time. */
        data class Undoable(val pending: TrashBin.Pending) : Outcome

        /** Nothing was changed. [message] says why in words fit for the owner. */
        data class Refused(val message: String) : Outcome

        /** What the change would remove is no longer what was shown; ask again with [preview]. */
        data class Changed(val preview: TrashBin.Preview) : Outcome
    }

    /**
     * Picks, from the live library inside the Undo engine's lock, the pictures to set aside so the
     * saved pictures fit the space available for [candidate]. An unknown measurement removes nothing.
     */
    fun chooser(
        measure: () -> StorageMeasurement,
        trashBytes: () -> Long,
        candidate: Long,
        selection: ImageSelection,
        providers: Set<AssetProvider>
    ) = TrashBin.Chooser { entries, state ->
        val budget = CacheBudget.compute(measure(), candidate, SelectionSnapshot.physicalBytes(entries) + trashBytes())
        if (!budget.known) {
            emptySet()
        } else {
            TrashBin.Chooser.toFit(selection, providers, budget.budgetBytes).choose(entries, state)
        }
    }

    /**
     * Commits [candidate]. When [expected] is given (the numbers the owner confirmed) and the live
     * library now disagrees, nothing is changed and [Outcome.Changed] carries the new numbers.
     */
    fun commit(trash: TrashBin, chooser: TrashBin.Chooser, candidate: Long, expected: TrashBin.Preview? = null): Outcome {
        if (expected != null) {
            val live = trash.preview(chooser)
            if (live.unreadable) return Outcome.Refused(UNREADABLE)
            if (live.pictures != expected.pictures || live.bytes != expected.bytes) return Outcome.Changed(live)
        }
        return when (val result = trash.begin(TrashBin.Kind.SHRINK, chooser, candidate)) {
            is TrashBin.BeginResult.Started -> Outcome.Undoable(result.pending)
            is TrashBin.BeginResult.NothingToRemove -> if (result.limitApplied) Outcome.Applied else Outcome.Refused(NOT_SAVED)
            is TrashBin.BeginResult.Busy -> Outcome.Refused(result.message)
            TrashBin.BeginResult.Unreadable -> Outcome.Refused(UNREADABLE)
            is TrashBin.BeginResult.Failed -> Outcome.Refused(result.reason)
        }
    }

    const val UNREADABLE = "Cosmos can't read its saved pictures right now, so nothing was changed."
    const val NOT_SAVED = "The new size could not be saved."
}
