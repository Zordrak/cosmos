package com.cosmos.screensaver.catalogue

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Crash-safe JSON text files with a retained last-good copy.
 *
 * A write goes to `<name>.tmp` (synced), the current file is then moved to `<name>.bak`, and the
 * temporary file takes its place. At no point is the only valid copy deleted. A reader tries the
 * file, then a completed temporary file (an interrupted replacement), then the backup.
 */
internal object AtomicJsonFile {
    sealed class Read {
        /** The file has never been written. */
        object Missing : Read()

        /** [text] validated; [recovered] is true when it came from the temporary or backup copy. */
        data class Ok(val text: String, val recovered: Boolean) : Read()

        /** Files exist but none validated. Nothing has been changed on disk. */
        data class Unreadable(val reason: String) : Read()
    }

    fun temporary(file: File) = File(file.parentFile, "${file.name}.tmp")
    fun backup(file: File) = File(file.parentFile, "${file.name}.bak")

    /** [accept] decides whether a candidate's text is valid (parses, supported version, ...). */
    fun read(file: File, accept: (String) -> Boolean = { true }): Read {
        val candidates = listOf(file to false, temporary(file) to true, backup(file) to true)
        var sawAny = false
        var failure = "unreadable"
        for ((candidate, recovered) in candidates) {
            if (!candidate.exists()) continue
            sawAny = true
            try {
                val text = candidate.readText(Charsets.UTF_8)
                if (accept(text)) return Read.Ok(text, recovered)
                failure = "${candidate.name} is not valid"
            } catch (error: IOException) {
                failure = "${candidate.name}: ${error.message}"
            }
        }
        return if (sawAny) Read.Unreadable(failure) else Read.Missing
    }

    @Throws(IOException::class)
    fun write(file: File, text: String, ops: FileOps = FileOps.Real) {
        file.parentFile?.mkdirs()
        val temporary = temporary(file)
        val backup = backup(file)
        ops.writeSynced(temporary, text.toByteArray(Charsets.UTF_8))
        val hadCurrent = file.exists()
        if (hadCurrent) {
            if (backup.exists() && !ops.delete(backup)) throw IOException("Unable to refresh ${backup.name}")
            if (!ops.rename(file, backup)) throw IOException("Unable to keep ${file.name} as backup")
        }
        if (!ops.rename(temporary, file)) {
            if (hadCurrent && ops.rename(backup, file)) ops.delete(temporary)
            throw IOException("Unable to install ${file.name}")
        }
    }
}

/** File operations that tests can interrupt or fail. */
internal interface FileOps {
    fun rename(from: File, to: File): Boolean
    fun delete(file: File): Boolean
    fun writeSynced(file: File, bytes: ByteArray)

    object Real : FileOps {
        override fun rename(from: File, to: File) = from.renameTo(to)
        override fun delete(file: File) = file.delete()
        override fun writeSynced(file: File, bytes: ByteArray) {
            FileOutputStream(file).use {
                it.write(bytes)
                it.fd.sync()
            }
        }
    }
}
