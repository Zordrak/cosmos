package com.cosmos.screensaver.settings

/** The TV's system inactivity timeout: the one setting Android uses to decide when the screensaver may start. */
internal interface SystemIdleSetting {
    fun canWrite(): Boolean

    /** The stored value in milliseconds, or null when the system holds none. May throw [SecurityException]. */
    fun read(): Int?

    /** Stores [millis]; false when the system refused. May throw [SecurityException]. */
    fun write(millis: Int): Boolean
}

/** What the TV currently says. Never an invented default: unknown stays unknown. */
internal sealed interface IdleReading {
    data class Known(val millis: Int) : IdleReading
    data class Unknown(val reason: Reason) : IdleReading
    object Unsupported : IdleReading

    enum class Reason { MISSING, NOT_POSITIVE }
}

/** The actual outcome of an attempt to change the timeout. */
internal sealed interface IdleWrite {
    data class Applied(val millis: Int) : IdleWrite
    object NeedsPermission : IdleWrite
    object NotAChoice : IdleWrite
    object Unsupported : IdleWrite

    /** The system refused the change, and this is what it holds now. */
    data class Refused(val reading: IdleReading) : IdleWrite

    /** The system accepted the change but reads back something else. */
    data class Mismatch(val reading: IdleReading) : IdleWrite
}

/** Reads and changes the TV-wide inactivity timeout, verifying every change by reading it back. */
internal class IdleTime(
    private val setting: SystemIdleSetting,
    private val log: (String, Throwable?) -> Unit = { _, _ -> }
) {
    fun read(): IdleReading {
        val raw = try {
            setting.read()
        } catch (error: SecurityException) {
            log("Cannot read the system idle timeout", error)
            return IdleReading.Unsupported
        }
        return when {
            raw == null -> IdleReading.Unknown(IdleReading.Reason.MISSING)
            raw <= 0 -> IdleReading.Unknown(IdleReading.Reason.NOT_POSITIVE)
            else -> IdleReading.Known(raw)
        }
    }

    fun canWrite(): Boolean = try {
        setting.canWrite()
    } catch (error: SecurityException) {
        log("Cannot check the write-settings permission", error)
        false
    }

    fun write(minutes: Int): IdleWrite {
        if (minutes !in CHOICE_MINUTES) return IdleWrite.NotAChoice
        if (read() == IdleReading.Unsupported) return IdleWrite.Unsupported
        if (!canWrite()) return IdleWrite.NeedsPermission
        val millis = minutes * MILLIS_PER_MINUTE
        val accepted = try {
            setting.write(millis)
        } catch (error: SecurityException) {
            log("The system refused the idle timeout change", error)
            return IdleWrite.NeedsPermission
        }
        val after = read()
        return when {
            after == IdleReading.Known(millis) -> IdleWrite.Applied(millis)
            !accepted -> IdleWrite.Refused(after)
            else -> IdleWrite.Mismatch(after)
        }
    }

    companion object {
        const val MILLIS_PER_MINUTE = 60_000
        val CHOICE_MINUTES = listOf(1, 2, 5, 10, 15, 30)
        val CHOICE_MILLIS = CHOICE_MINUTES.map { it * MILLIS_PER_MINUTE }

        /** A whole-minute value as minutes, otherwise null, so odd system values can be shown as they are. */
        fun wholeMinutes(millis: Int): Int? = if (millis > 0 && millis % MILLIS_PER_MINUTE == 0) millis / MILLIS_PER_MINUTE else null

        /** Splits a custom value into whole minutes and the seconds left over (rounded to the nearest second). */
        fun minutesAndSeconds(millis: Int): Pair<Int, Int> {
            val totalSeconds = (millis + 500) / 1000
            return totalSeconds / 60 to totalSeconds % 60
        }
    }
}
