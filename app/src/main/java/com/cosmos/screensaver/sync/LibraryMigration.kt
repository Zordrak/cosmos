package com.cosmos.screensaver.sync

import com.cosmos.screensaver.catalogue.AtomicJsonFile
import com.cosmos.screensaver.catalogue.CatalogueJson
import com.cosmos.screensaver.catalogue.FileOps
import org.json.JSONException
import java.io.File
import java.io.IOException

/**
 * Moves the saved library from the old location (the app cache directory, where Cosmos 0.4.0
 * and earlier kept it) into app data, where Android's "Clear cache" and cache reclaim cannot
 * touch it. Only same-volume renames are used (never a copy, never a delete of the only copy),
 * and the catalogue moves last. If the process dies part-way, running it again resumes from the
 * mixed old/new state.
 */
internal object LibraryMigration {
    sealed class Result {
        /** There was nothing to move. */
        object NotNeeded : Result()

        /**
         * The library is in place. [movedImages] were moved this run; [missingFiles] are catalogue
         * entries whose picture file was not found (kept for repair); [leftover] are old files that
         * could not be safely merged and were preserved where they were.
         */
        data class Ready(
            val movedImages: Int,
            val missingFiles: Int = 0,
            val leftover: Int = 0,
            val notes: List<String> = emptyList()
        ) : Result()

        data class Failed(val reason: String) : Result()
    }

    fun migrate(oldDirectory: File, newDirectory: File, ops: FileOps = FileOps.Real): Result {
        val oldCatalogue = File(oldDirectory, "catalogue.json")
        val oldImages = File(oldDirectory, "images")
        val newCatalogue = File(newDirectory, "catalogue.json")
        val newImages = File(newDirectory, "images")

        val oldHasCatalogue = listOf(oldCatalogue, AtomicJsonFile.temporary(oldCatalogue), AtomicJsonFile.backup(oldCatalogue))
            .any { it.exists() }
        val oldFiles = oldImages.listFiles()?.filter { it.isFile }.orEmpty()
        if (!oldHasCatalogue && oldFiles.isEmpty()) return Result.NotNeeded

        // A catalogue already in app data is authoritative: never merge over it or move old
        // files into the middle of it.
        if (newCatalogue.exists()) {
            if (!oldCatalogue.exists() && oldFiles.isEmpty()) {
                // Only stale temporary/backup copies of an index that has already moved remain.
                listOf(AtomicJsonFile.temporary(oldCatalogue), AtomicJsonFile.backup(oldCatalogue)).forEach {
                    if (it.exists()) ops.delete(it)
                }
                return Result.NotNeeded
            }
            return Result.Ready(
                movedImages = 0,
                leftover = oldFiles.size + if (oldHasCatalogue) 1 else 0,
                notes = listOf("An older library was left in the cache directory untouched.")
            )
        }

        try {
            if (!newImages.isDirectory && !newImages.mkdirs()) return Result.Failed("Unable to create the library directory")
            val notes = mutableListOf<String>()
            var moved = 0
            var leftover = 0
            for (source in oldFiles) {
                val destination = File(newImages, source.name)
                when {
                    !destination.exists() -> {
                        if (!ops.rename(source, destination)) return Result.Failed("Unable to move ${source.name}")
                        moved++
                    }
                    destination.length() == source.length() -> {
                        // Resumed after an interruption: this exact file is already in place.
                        if (!ops.delete(source)) leftover++
                    }
                    else -> {
                        leftover++
                        notes += "${source.name} differs from the file already in the library and was kept in place."
                    }
                }
            }

            var missing = 0
            if (oldHasCatalogue) {
                val read = AtomicJsonFile.read(oldCatalogue) { text ->
                    try {
                        CatalogueJson.decode(text)
                        true
                    } catch (_: JSONException) {
                        false
                    }
                }
                when (read) {
                    is AtomicJsonFile.Read.Ok -> {
                        val referenced = CatalogueJson.decode(read.text).entries
                        missing = referenced.count { !File(newImages, it.fileName).exists() }
                        if (read.recovered) {
                            // Install the recovered content as the library; the damaged original stays.
                            AtomicJsonFile.write(newCatalogue, read.text, ops)
                            notes += "The library index was recovered from a backup copy."
                        } else if (!ops.rename(oldCatalogue, newCatalogue)) {
                            return Result.Failed("Unable to move the library index")
                        }
                    }
                    is AtomicJsonFile.Read.Unreadable -> {
                        notes += "The old library index was damaged (${read.reason}); pictures were kept and it will be rebuilt."
                        val preserved = File(newDirectory, "catalogue.json.corrupt-migrated")
                        if (oldCatalogue.exists() && !preserved.exists()) ops.rename(oldCatalogue, preserved)
                    }
                    AtomicJsonFile.Read.Missing -> Unit
                }
                listOf(AtomicJsonFile.temporary(oldCatalogue), AtomicJsonFile.backup(oldCatalogue)).forEach {
                    if (it.exists()) ops.delete(it)
                }
            }
            if (oldImages.isDirectory && oldImages.list().isNullOrEmpty()) oldImages.delete()
            return Result.Ready(moved, missing, leftover, notes)
        } catch (error: IOException) {
            return Result.Failed(error.message ?: error.javaClass.simpleName)
        }
    }
}
