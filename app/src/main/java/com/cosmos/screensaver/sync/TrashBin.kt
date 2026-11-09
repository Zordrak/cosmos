package com.cosmos.screensaver.sync

import android.os.SystemClock
import android.util.Log
import com.cosmos.screensaver.catalogue.AtomicJsonFile
import com.cosmos.screensaver.catalogue.CatalogueEntry
import com.cosmos.screensaver.catalogue.CatalogueJson
import com.cosmos.screensaver.catalogue.CatalogueStore
import com.cosmos.screensaver.catalogue.FileOps
import com.cosmos.screensaver.catalogue.LibraryMaintenance
import com.cosmos.screensaver.catalogue.StoreRead
import com.cosmos.screensaver.catalogue.UserState
import com.cosmos.screensaver.catalogue.UserStateRead
import com.cosmos.screensaver.catalogue.UserStateStore
import com.cosmos.screensaver.rights.AssetProvider
import com.cosmos.screensaver.settings.CosmosSettings
import com.cosmos.screensaver.settings.ImageSelection
import com.cosmos.screensaver.settings.SelectionSnapshot
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Reads and writes the owner's chosen size with a result that says whether it reached storage. */
internal interface LimitPort {
    fun current(): Long
    fun write(value: Long): Boolean
}

/**
 * The one way saved pictures are removed on purpose in bulk (a smaller size, Tidy up, Clear) and
 * the one place Undo lives. Nothing is deleted when a change is made: the pictures' files are moved
 * into `trash/<operation>/files` and the entries' full metadata is written to a journal there, so
 * Undo within [UNDO_WINDOW_MILLIS] puts everything back exactly. The files are deleted only when the
 * window has passed.
 *
 * A change is PREPARED (journal written, nothing moved), the files are moved and the catalogue (and
 * any size) updated, and COMMITTED is written last. A crash before COMMITTED is rolled back from the
 * journal at the next start; COMMITTED stays undoable until its deadline; RESTORING and FINALIZING
 * are written before the first move/delete so a restart finishes the recorded intent. A journal that
 * cannot be read is reported and its files are left alone.
 *
 * Everything runs under the shared library lock ([CatalogueStore.locked]) and never touches
 * anything outside `trash/<operation>` and the library's own image files.
 */
internal class TrashBin(
    private val store: CatalogueStore,
    private val userState: UserStateStore,
    private val limit: LimitPort? = null,
    private val ops: FileOps = FileOps.Real,
    private val monotonicMillis: () -> Long = { SystemClock.elapsedRealtime() },
    private val wallMillis: () -> Long = { System.currentTimeMillis() },
    private val undoWindowMillis: Long = UNDO_WINDOW_MILLIS,
    private val scheduleExpiry: ((delayMillis: Long, task: () -> Unit) -> Unit)? = null
) {
    enum class Kind { SHRINK, TIDY, CLEAR }
    enum class Phase { PREPARED, COMMITTED, RESTORING, FINALIZING }

    /** Decides, from the LIVE library, which pictures (by identity) a change removes. Favourites are always dropped from the answer. */
    fun interface Chooser {
        fun choose(entries: List<CatalogueEntry>, state: UserState): Set<String>

        companion object {
            /** Every picture. */
            fun everything() = Chooser { entries, _ -> entries.map(SelectionSnapshot::identity).toSet() }

            /** Pictures the current view settings would not show. */
            fun notShown(selection: ImageSelection, providers: Set<AssetProvider>) = Chooser { entries, state ->
                val inView = SelectionSnapshot.build(entries, selection, providers, state).eligibleIdentities
                entries.map(SelectionSnapshot::identity).toSet() - inView
            }

            /** Enough pictures, in eviction order, to bring the saved bytes down to [budgetBytes]. */
            fun toFit(selection: ImageSelection, providers: Set<AssetProvider>, budgetBytes: Long) = Chooser { entries, state ->
                val inView = SelectionSnapshot.build(entries, selection, providers, state).eligibleIdentities
                var bytes = SelectionSnapshot.physicalBytes(entries)
                val chosen = LinkedHashSet<String>()
                for (group in CacheTrimmer.candidates(entries, state, inView)) {
                    if (bytes <= budgetBytes) break
                    chosen += group.identity
                    bytes -= group.bytes
                }
                chosen
            }
        }
    }

    /** What a change would remove right now; recomputed from live state when it is confirmed. */
    data class Preview(
        val pictures: Int,
        val files: Int,
        val bytes: Long,
        /** Chosen pictures kept because they are favourites. */
        val favouritesKept: Int,
        val unreadable: Boolean = false
    )

    /** A change that can still be undone. */
    data class Pending(
        val id: String,
        val kind: Kind,
        val pictures: Int,
        val files: Int,
        val bytes: Long,
        val remainingMillis: Long,
        /** Undo also puts the previous size back. */
        val restoresLimit: Boolean
    )

    sealed interface BeginResult {
        data class Started(val pending: Pending) : BeginResult

        /** Nothing needed removing; [limitApplied] says the new size was saved. */
        data class NothingToRemove(val limitApplied: Boolean) : BeginResult

        /** An earlier change can still be undone. */
        data class Busy(val message: String = BUSY_MESSAGE) : BeginResult

        /** The saved pictures or the saved choices could not be read; nothing was touched. */
        data object Unreadable : BeginResult

        /** Rolled back unless [recoverable] says part of it is left for the next start to finish. */
        data class Failed(val reason: String, val recoverable: Boolean) : BeginResult
    }

    data class UndoResult(
        val status: Status,
        val restoredPictures: Int = 0,
        val failedPictures: Int = 0,
        /** Not brought back because they were blocked, or something newer already holds their place. */
        val skippedPictures: Int = 0,
        val limitRestored: Boolean = false,
        val message: String = ""
    ) {
        enum class Status { NOTHING_TO_UNDO, EXPIRED, DONE, PARTIAL, FAILED }
    }

    data class RecoveryReport(
        val rolledBack: Int = 0,
        val finalized: Int = 0,
        val resumed: Int = 0,
        val problems: List<String> = emptyList()
    )

    private data class Move(val fileName: String, val bytes: Long)

    private data class Journal(
        val id: String,
        val kind: Kind,
        val createdAt: Long,
        val oldLimit: Long,
        val newLimit: Long,
        val moves: List<Move>,
        val entries: List<CatalogueEntry>
    )

    private data class PhaseRecord(val phase: Phase, val deadlineWall: Long)

    private sealed interface TokenRead {
        data class Ok(val journal: Journal, val phase: PhaseRecord) : TokenRead
        object Empty : TokenRead
        data class Malformed(val reason: String) : TokenRead
    }

    private class Active(
        val id: String,
        val kind: Kind,
        val pictures: Int,
        val files: Int,
        val bytes: Long,
        val keys: Set<String>,
        val hashes: Set<String>,
        val deadlineMono: Long,
        val restoresLimit: Boolean
    )

    private class Selection(val entries: List<CatalogueEntry>, val moves: List<Move>, val pictures: Int, val favouritesKept: Int)

    private class Restore(
        val restored: Int = 0,
        val failed: Int = 0,
        val skipped: Int = 0,
        val limitRestored: Boolean = false,
        val complete: Boolean = false,
        val unreadable: Boolean = false,
        val note: String = ""
    )

    private enum class EntryOutcome { RESTORED, SKIPPED, FAILED }

    private val root = File(store.directory, TRASH_DIRECTORY)
    private val counter = AtomicInteger()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val settled = AtomicBoolean(false)

    @Volatile
    private var active: Active? = null

    @Volatile
    private var loaded = false

    @Volatile
    private var lastProblems: List<String> = emptyList()

    /** Problems found the last time unfinished changes were recovered; their files were left alone. */
    val recoveryProblems: List<String> get() = lastProblems

    /** The change that can still be undone, if any. Cheap; never touches the disk. */
    fun pending(): Pending? {
        val a = active ?: return null
        return Pending(
            a.id, a.kind, a.pictures, a.files, a.bytes,
            (a.deadlineMono - monotonicMillis()).coerceAtLeast(0L), a.restoresLimit
        )
    }

    /**
     * True when [key] or the picture [sha256] was removed by the change that can still be undone, so
     * a download that finishes meanwhile must not put it back. Never touches the disk.
     */
    fun isStashed(key: String, sha256: String): Boolean {
        val a = active ?: return false
        return key in a.keys || (sha256.isNotBlank() && sha256 in a.hashes)
    }

    /** Bytes still on disk in the trash (removed pictures awaiting Undo expiry); they are not free space. */
    fun bytesOnDisk(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Runs [listener] (on whichever thread settles the change) whenever no change is awaiting Undo any more. */
    fun addSettledListener(listener: () -> Unit) {
        listeners += listener
    }

    /** What [chooser] would remove from the live library, without changing anything. */
    fun preview(chooser: Chooser): Preview = CatalogueStore.locked {
        val selection = select(chooser) ?: return@locked Preview(0, 0, 0L, 0, unreadable = true)
        Preview(selection.pictures, selection.moves.size, selection.moves.sumOf { it.bytes }, selection.favouritesKept)
    }

    /**
     * Removes what [chooser] picks from the LIVE library (favourites are always kept) and, when
     * [newLimit] is given, saves it as the size - all undoable together. Refuses while an earlier
     * change can still be undone.
     */
    fun begin(kind: Kind, chooser: Chooser, newLimit: Long? = null): BeginResult {
        require(newLimit == null || limit != null) { "A size change needs a LimitPort" }
        ensureLoaded()
        val result = CatalogueStore.locked {
            recoverLocked(wallMillis())
            if (active != null) BeginResult.Busy() else beginLocked(kind, chooser, newLimit)
        }
        fireSettled()
        return result
    }

    /** Puts the pending change back, using the live state; reports what actually came back. */
    fun undo(): UndoResult {
        ensureLoaded()
        val result = CatalogueStore.locked { undoLocked() }
        fireSettled()
        return result
    }

    /**
     * Rolls back changes a crash interrupted, finishes ones already being undone or finalized, adopts
     * an unexpired committed change (so Undo survives a restart), and finalizes expired ones. Never
     * deletes a change whose Undo window is still open.
     */
    fun recoverAndExpire(nowMillis: Long = wallMillis()): RecoveryReport {
        val report = CatalogueStore.locked { recoverLocked(nowMillis).also { loaded = true } }
        fireSettled()
        return report
    }

    private fun ensureLoaded() {
        if (!loaded) recoverAndExpire()
    }

    private fun fireSettled() {
        if (settled.getAndSet(false)) listeners.forEach { runCatching(it) }
    }

    private fun clearActive() {
        if (active != null) {
            active = null
            settled.set(true)
        }
    }

    private fun select(chooser: Chooser): Selection? {
        val entries = (store.read() as? StoreRead.Ok)?.catalogue?.entries ?: return null
        val state = (userState.read() as? UserStateRead.Ok)?.state ?: return null
        val byIdentity = entries.groupBy(SelectionSnapshot::identity)
        var favouritesKept = 0
        val picked = mutableSetOf<String>()
        for (identity in chooser.choose(entries, state)) {
            val aliases = byIdentity[identity] ?: continue
            if (aliases.any { state.isFavourite(it.sha256) }) {
                favouritesKept++
                continue
            }
            val files = aliases.map { store.imageFile(it) }
            if (files.any { !it.isFile || LibraryMaintenance.isClaimed(it) }) continue
            picked += identity
        }
        val removed = entries.filter { SelectionSnapshot.identity(it) in picked }
        val retainedFiles = entries.filter { SelectionSnapshot.identity(it) !in picked }.map { it.fileName }.toSet()
        val moves = removed.distinctBy { it.fileName }
            .filter { it.fileName !in retainedFiles }
            .map { Move(it.fileName, store.imageFile(it).length()) }
        return Selection(removed, moves, picked.size, favouritesKept)
    }

    private fun beginLocked(kind: Kind, chooser: Chooser, newLimit: Long?): BeginResult {
        val selection = select(chooser) ?: return BeginResult.Unreadable
        val oldLimit = if (newLimit != null) limit!!.current() else NO_LIMIT
        if (selection.entries.isEmpty()) {
            if (newLimit == null) return BeginResult.NothingToRemove(false)
            return if (limit!!.write(newLimit)) BeginResult.NothingToRemove(true)
            else BeginResult.Failed("The new size could not be saved.", false)
        }
        val id = newId()
        val journal = Journal(id, kind, wallMillis(), oldLimit, newLimit ?: NO_LIMIT, selection.moves, selection.entries)
        try {
            if (!filesDirectory(id).mkdirs()) throw IOException("Unable to create the Undo folder")
            writePayload(journal)
            writePhase(id, Phase.PREPARED, 0L)
        } catch (error: IOException) {
            disposeToken(journal)
            return BeginResult.Failed("Nothing was changed (${error.message}).", false)
        }
        for (move in selection.moves) {
            val from = File(store.imagesDirectory, move.fileName)
            if (!ops.rename(from, trashFile(id, move.fileName))) return abort(journal, "A picture could not be set aside.")
        }
        try {
            val removedKeys = selection.entries.map { it.key }.toSet()
            store.update { current -> current.copy(entries = current.entries.filterNot { it.key in removedKeys }) }
        } catch (error: IOException) {
            return abort(journal, "The saved pictures could not be updated.")
        }
        if (newLimit != null && !limit!!.write(newLimit)) return abort(journal, "The new size could not be saved.")
        val deadlineWall = wallMillis() + undoWindowMillis
        try {
            writePhase(id, Phase.COMMITTED, deadlineWall)
        } catch (error: IOException) {
            return abort(journal, "The change could not be recorded.")
        }
        val committed = adopt(journal, monotonicMillis() + undoWindowMillis)
        scheduleExpiryFor(undoWindowMillis)
        return BeginResult.Started(pendingOf(committed))
    }

    private fun abort(journal: Journal, reason: String): BeginResult {
        val outcome = restoreLocked(journal)
        return BeginResult.Failed(reason, recoverable = !outcome.complete)
    }

    private fun pendingOf(a: Active) = Pending(
        a.id, a.kind, a.pictures, a.files, a.bytes, (a.deadlineMono - monotonicMillis()).coerceAtLeast(0L), a.restoresLimit
    )

    private fun adopt(journal: Journal, deadlineMono: Long): Active {
        val entry = Active(
            id = journal.id,
            kind = journal.kind,
            pictures = journal.entries.map(SelectionSnapshot::identity).toSet().size,
            files = journal.moves.size,
            bytes = journal.moves.sumOf { it.bytes },
            keys = journal.entries.map { it.key }.toSet(),
            hashes = journal.entries.map { it.sha256 }.filter { it.isNotBlank() }.toSet(),
            deadlineMono = deadlineMono,
            restoresLimit = journal.oldLimit != NO_LIMIT && journal.newLimit != NO_LIMIT
        )
        active = entry
        return entry
    }

    private fun scheduleExpiryFor(delayMillis: Long) {
        scheduleExpiry?.invoke(delayMillis + EXPIRY_SLACK_MILLIS) { recoverAndExpire() }
    }

    private fun undoLocked(): UndoResult {
        val current = active ?: return UndoResult(UndoResult.Status.NOTHING_TO_UNDO)
        if (monotonicMillis() >= current.deadlineMono) {
            expireLocked(current)
            return UndoResult(UndoResult.Status.EXPIRED, message = "The time to undo has passed.")
        }
        val token = readToken(current.id) as? TokenRead.Ok
            ?: return UndoResult(UndoResult.Status.FAILED, message = "The removed pictures could not be found.")
        try {
            writePhase(current.id, Phase.RESTORING, token.phase.deadlineWall)
        } catch (error: IOException) {
            return UndoResult(UndoResult.Status.FAILED, message = "Undo could not start; nothing was changed.")
        }
        clearActive()
        val outcome = restoreLocked(token.journal)
        val status = when {
            outcome.unreadable -> UndoResult.Status.FAILED
            outcome.complete && outcome.failed == 0 -> UndoResult.Status.DONE
            else -> UndoResult.Status.PARTIAL
        }
        return UndoResult(
            status, outcome.restored, outcome.failed, outcome.skipped, outcome.limitRestored,
            if (status == UndoResult.Status.DONE) "" else outcome.note.ifBlank { "Some pictures could not be restored." }
        )
    }

    private fun expireLocked(current: Active) {
        val token = readToken(current.id) as? TokenRead.Ok
        clearActive()
        if (token != null) finalizeLocked(token.journal)
    }

    private fun finalizeLocked(journal: Journal): Boolean {
        try {
            writePhase(journal.id, Phase.FINALIZING, 0L)
        } catch (error: IOException) {
            Log.w(TAG, "Unable to record finalizing ${journal.id}", error)
            return false
        }
        return disposeToken(journal)
    }

    private fun recoverLocked(nowMillis: Long): RecoveryReport {
        var rolledBack = 0
        var finalized = 0
        var resumed = 0
        val problems = mutableListOf<String>()
        val directories = root.listFiles { file -> file.isDirectory && TOKEN_NAME.matches(file.name) }
            ?.sortedBy { it.name }.orEmpty()
        for (directory in directories) {
            when (val token = readToken(directory.name)) {
                TokenRead.Empty -> removeEmptyShell(directory)
                is TokenRead.Malformed -> problems += "${directory.name}: ${token.reason}"
                is TokenRead.Ok -> when (token.phase.phase) {
                    Phase.PREPARED -> {
                        if (restoreLocked(token.journal).complete) rolledBack++
                        else problems += "${directory.name}: could not finish rolling back; will retry"
                    }
                    Phase.RESTORING -> {
                        if (restoreLocked(token.journal).complete) resumed++
                        else problems += "${directory.name}: could not finish restoring; will retry"
                    }
                    Phase.FINALIZING -> {
                        if (disposeToken(token.journal)) finalized++
                        else problems += "${directory.name}: could not finish removing; will retry"
                    }
                    Phase.COMMITTED -> recoverCommitted(token, nowMillis)?.let { if (it) finalized++ else problems += "${directory.name}: could not remove expired files; will retry" }
                }
            }
        }
        lastProblems = problems
        return RecoveryReport(rolledBack, finalized, resumed, problems)
    }

    /** Null when the change stays pending; otherwise whether finalizing it succeeded. */
    private fun recoverCommitted(token: TokenRead.Ok, nowMillis: Long): Boolean? {
        val journal = token.journal
        val known = active
        if (known != null && known.id == journal.id) {
            if (monotonicMillis() < known.deadlineMono) return null
            clearActive()
            return finalizeLocked(journal)
        }
        val remaining = token.phase.deadlineWall - nowMillis
        val bounded = if (remaining > undoWindowMillis) undoWindowMillis else remaining
        if (bounded <= 0L) return finalizeLocked(journal)
        if (active == null) {
            adopt(journal, monotonicMillis() + bounded)
            scheduleExpiryFor(bounded)
        }
        return null
    }

    /**
     * Puts the journal's pictures back using the LIVE state: blocked pictures stay out, a newer entry
     * for the same source is not overwritten, an existing valid file is not replaced, and files
     * come back before their entries become visible. Idempotent, so a restart can run it again.
     * Disposes of the token only when everything that could be restored is done.
     */
    private fun restoreLocked(journal: Journal): Restore {
        val state = (userState.read() as? UserStateRead.Ok)?.state
        val live = (store.read() as? StoreRead.Ok)?.catalogue
        if (state == null || live == null) {
            return Restore(unreadable = true, note = "The saved pictures could not be read; nothing was changed.")
        }
        val liveByKey = live.entries.associateBy { it.key }
        val outcomes = mutableMapOf<CatalogueEntry, EntryOutcome>()
        val wanted = mutableListOf<CatalogueEntry>()
        for (entry in journal.entries) {
            val existing = liveByKey[entry.key]
            when {
                state.isBlocked(entry.key, entry.sha256) -> outcomes[entry] = EntryOutcome.SKIPPED
                existing != null && existing.sha256 != entry.sha256 -> outcomes[entry] = EntryOutcome.SKIPPED
                else -> wanted += entry
            }
        }

        val claimed = mutableListOf<File>()
        val movedBack = mutableListOf<String>()
        val fileOk = mutableMapOf<String, Boolean>()
        fun ensureFile(name: String): Boolean = fileOk.getOrPut(name) {
            val target = File(store.imagesDirectory, name)
            if (target.isFile) return@getOrPut true
            val source = trashFile(journal.id, name)
            if (!source.isFile) return@getOrPut false
            store.imagesDirectory.mkdirs()
            LibraryMaintenance.claim(target)
            claimed += target
            val moved = ops.rename(source, target)
            if (moved) movedBack += name
            moved
        }

        var complete = true
        var note = ""
        try {
            val toAdd = mutableListOf<CatalogueEntry>()
            for (entry in wanted) {
                if (ensureFile(entry.fileName)) {
                    if (liveByKey[entry.key] == null) toAdd += entry else outcomes[entry] = EntryOutcome.RESTORED
                } else {
                    outcomes[entry] = EntryOutcome.FAILED
                }
            }
            if (toAdd.isNotEmpty()) {
                try {
                    store.update { current ->
                        val present = current.entries.map { it.key }.toSet()
                        current.copy(entries = current.entries + toAdd.filter { it.key !in present })
                    }
                    toAdd.forEach { outcomes[it] = EntryOutcome.RESTORED }
                } catch (error: IOException) {
                    toAdd.forEach { outcomes[it] = EntryOutcome.FAILED }
                    movedBack.forEach { name -> ops.rename(File(store.imagesDirectory, name), trashFile(journal.id, name)) }
                    complete = false
                    note = "The saved pictures could not be updated."
                }
            }
        } finally {
            claimed.forEach(LibraryMaintenance::release)
        }

        var limitRestored = false
        if (journal.newLimit != NO_LIMIT && journal.oldLimit != NO_LIMIT && limit != null) {
            val now = limit.current()
            if (now == journal.newLimit && journal.oldLimit != journal.newLimit) {
                limitRestored = limit.write(journal.oldLimit)
                if (!limitRestored) {
                    complete = false
                    note = "The previous size could not be saved."
                }
            }
        }

        if (complete && !disposeToken(journal)) {
            complete = false
            note = "Pictures are back, but some leftover files could not be tidied away."
        }

        val byIdentity = outcomes.entries.groupBy({ SelectionSnapshot.identity(it.key) }, { it.value })
        val restored = byIdentity.count { (_, list) -> EntryOutcome.RESTORED in list }
        val failed = byIdentity.count { (_, list) -> EntryOutcome.RESTORED !in list && EntryOutcome.FAILED in list }
        val skipped = byIdentity.size - restored - failed
        return Restore(restored, failed, skipped, limitRestored, complete, note = note)
    }

    /**
     * Deletes exactly this token's owned files, then its journal, then the (now empty) folders.
     * Unknown files and every other path are left alone. False when anything could not be removed.
     */
    private fun disposeToken(journal: Journal): Boolean {
        for (move in journal.moves) {
            val file = trashFile(journal.id, move.fileName)
            if (file.exists() && !ops.delete(file)) return false
        }
        for (base in listOf(payloadFile(journal.id), phaseFile(journal.id))) {
            for (variant in listOf(base, AtomicJsonFile.temporary(base), AtomicJsonFile.backup(base))) {
                if (variant.exists() && !ops.delete(variant)) return false
            }
        }
        filesDirectory(journal.id).delete()
        tokenDirectory(journal.id).delete()
        return true
    }

    private fun removeEmptyShell(directory: File) {
        // Only reached when nothing is left to restore or delete, so every file here is bookkeeping.
        for (base in listOf(payloadFile(directory.name), phaseFile(directory.name))) {
            listOf(base, AtomicJsonFile.temporary(base), AtomicJsonFile.backup(base)).forEach { if (it.exists()) ops.delete(it) }
        }
        File(directory, FILES_DIRECTORY).delete()
        directory.delete()
    }

    private fun newId(): String {
        while (true) {
            val id = "op-${wallMillis()}-${"%04x".format(counter.incrementAndGet() and 0xffff)}"
            if (!tokenDirectory(id).exists()) return id
        }
    }

    private fun tokenDirectory(id: String) = File(root, id)
    private fun filesDirectory(id: String) = File(tokenDirectory(id), FILES_DIRECTORY)
    private fun trashFile(id: String, name: String) = File(filesDirectory(id), name)
    private fun payloadFile(id: String) = File(tokenDirectory(id), PAYLOAD_FILE)
    private fun phaseFile(id: String) = File(tokenDirectory(id), PHASE_FILE)

    @Throws(IOException::class)
    private fun writePayload(journal: Journal) {
        val json = JSONObject()
            .put("formatVersion", FORMAT_VERSION)
            .put("id", journal.id)
            .put("kind", journal.kind.name)
            .put("createdAt", journal.createdAt)
            .put("oldLimit", journal.oldLimit)
            .put("newLimit", journal.newLimit)
            .put("moves", JSONArray().apply {
                journal.moves.forEach { put(JSONObject().put("file", it.fileName).put("bytes", it.bytes)) }
            })
            .put("entries", JSONArray().apply { journal.entries.forEach { put(CatalogueJson.encodeEntry(it)) } })
        AtomicJsonFile.write(payloadFile(journal.id), json.toString(), ops)
    }

    @Throws(IOException::class)
    private fun writePhase(id: String, phase: Phase, deadlineWall: Long) {
        val json = JSONObject().put("formatVersion", FORMAT_VERSION).put("phase", phase.name).put("deadline", deadlineWall)
        AtomicJsonFile.write(phaseFile(id), json.toString(), ops)
    }

    private fun readToken(id: String): TokenRead {
        val payloadRead = AtomicJsonFile.read(payloadFile(id)) { runCatching { decodePayload(id, it) }.isSuccess }
        var phaseRead = AtomicJsonFile.read(phaseFile(id)) { runCatching { decodePhase(it) }.isSuccess }
        // Only a torn temporary copy exists: the very first record never finished, so nothing was moved.
        if (phaseRead is AtomicJsonFile.Read.Unreadable && !phaseFile(id).exists() && !AtomicJsonFile.backup(phaseFile(id)).exists()) {
            phaseRead = AtomicJsonFile.Read.Missing
        }
        val nothingMoved = phaseRead is AtomicJsonFile.Read.Missing &&
            filesDirectory(id).listFiles().isNullOrEmpty()
        return when {
            payloadRead is AtomicJsonFile.Read.Missing && phaseRead is AtomicJsonFile.Read.Missing -> TokenRead.Empty
            // The journal is deleted before the progress record, so only leftovers of a finished disposal remain.
            payloadRead is AtomicJsonFile.Read.Missing && phaseRead is AtomicJsonFile.Read.Ok -> TokenRead.Empty
            payloadRead is AtomicJsonFile.Read.Unreadable && nothingMoved -> TokenRead.Empty
            payloadRead is AtomicJsonFile.Read.Unreadable -> TokenRead.Malformed("the journal is unreadable (${payloadRead.reason})")
            payloadRead is AtomicJsonFile.Read.Missing -> TokenRead.Malformed("the journal is missing")
            phaseRead is AtomicJsonFile.Read.Unreadable -> TokenRead.Malformed("the progress record is unreadable (${phaseRead.reason})")
            payloadRead is AtomicJsonFile.Read.Ok -> {
                // A missing progress record means PREPARED was never written, so nothing was moved.
                val phase = (phaseRead as? AtomicJsonFile.Read.Ok)?.let { decodePhase(it.text) }
                    ?: PhaseRecord(Phase.PREPARED, 0L)
                TokenRead.Ok(decodePayload(id, payloadRead.text), phase)
            }
            else -> TokenRead.Malformed("the journal is unreadable")
        }
    }

    private fun decodePhase(text: String): PhaseRecord {
        val json = JSONObject(text)
        if (json.optInt("formatVersion", -1) != FORMAT_VERSION) throw JSONException("Unsupported progress record")
        return PhaseRecord(Phase.valueOf(json.getString("phase")), json.getLong("deadline"))
    }

    private fun decodePayload(id: String, text: String): Journal {
        val json = JSONObject(text)
        if (json.optInt("formatVersion", -1) != FORMAT_VERSION) throw JSONException("Unsupported journal")
        if (json.getString("id") != id) throw JSONException("Journal belongs to another change")
        val movesJson = json.getJSONArray("moves")
        val moves = (0 until movesJson.length()).map { index ->
            val item = movesJson.getJSONObject(index)
            val name = item.getString("file")
            if (!LIBRARY_FILE.matches(name)) throw JSONException("Unexpected file name")
            Move(name, item.getLong("bytes"))
        }
        val entriesJson = json.getJSONArray("entries")
        val entries = (0 until entriesJson.length()).map { CatalogueJson.decodeEntry(entriesJson.getJSONObject(it)) }
        return Journal(
            id, Kind.valueOf(json.getString("kind")), json.getLong("createdAt"),
            json.getLong("oldLimit"), json.getLong("newLimit"), moves.distinctBy { it.fileName }, entries
        )
    }

    companion object {
        private const val TAG = "CosmosTrash"
        const val UNDO_WINDOW_MILLIS = 15_000L
        const val BUSY_MESSAGE = "Undo the previous change or wait for it to finish."
        private const val TRASH_DIRECTORY = "trash"
        private const val FILES_DIRECTORY = "files"
        private const val PAYLOAD_FILE = "journal.json"
        private const val PHASE_FILE = "phase.json"
        private const val FORMAT_VERSION = 1
        private const val NO_LIMIT = -1L
        private const val EXPIRY_SLACK_MILLIS = 100L
        private val TOKEN_NAME = Regex("^op-[0-9]{1,20}-[0-9a-f]{4}$")
        private val LIBRARY_FILE = Regex("^[0-9a-f]{64}\\.(jpg|png)$")

        private val instances = HashMap<String, TrashBin>()
        private val expiryExecutor by lazy {
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "cosmos-undo-expiry").apply { isDaemon = true }
            }
        }

        /** The one engine for this app's library, so every screen, job and service sees the same Undo. */
        fun of(context: android.content.Context): TrashBin {
            val bin = create(context, LibraryStorage.store(context), LibraryStorage.userState(context))
            bin.ensureLoaded()
            return bin
        }

        internal fun create(context: android.content.Context, store: CatalogueStore, userState: UserStateStore): TrashBin =
            synchronized(instances) {
                instances.getOrPut(store.directory.absolutePath) {
                    val settings = CosmosSettings(context.applicationContext)
                    TrashBin(
                        store = store,
                        userState = userState,
                        limit = object : LimitPort {
                            override fun current() = settings.cacheLimitBytes
                            override fun write(value: Long) = settings.writeCacheLimit(value)
                        },
                        scheduleExpiry = { delay, task ->
                            expiryExecutor.schedule({ runCatching(task) }, delay, TimeUnit.MILLISECONDS)
                        }
                    )
                }
            }
    }
}
