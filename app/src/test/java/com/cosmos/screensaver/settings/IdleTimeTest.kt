package com.cosmos.screensaver.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleTimeTest {
    private class FakeSetting(
        var stored: Int? = 600_000,
        var writable: Boolean = true,
        var accepts: Boolean = true,
        var storesWhatWasWritten: Boolean = true,
        var readThrows: Boolean = false,
        var writeThrows: Boolean = false
    ) : SystemIdleSetting {
        var writes = 0
        override fun canWrite() = writable
        override fun read(): Int? {
            if (readThrows) throw SecurityException("no read")
            return stored
        }

        override fun write(millis: Int): Boolean {
            writes++
            if (writeThrows) throw SecurityException("revoked")
            if (accepts && storesWhatWasWritten) stored = millis
            return accepts
        }
    }

    @Test
    fun choicesAreTheOfferedMinutes() {
        assertEquals(listOf(1, 2, 5, 10, 15, 30), IdleTime.CHOICE_MINUTES)
        assertEquals(listOf(60_000, 120_000, 300_000, 600_000, 900_000, 1_800_000), IdleTime.CHOICE_MILLIS)
    }

    @Test
    fun readsTheActualValueIncludingCustomOnes() {
        assertEquals(IdleReading.Known(600_000), IdleTime(FakeSetting(600_000)).read())
        assertEquals(IdleReading.Known(450_000), IdleTime(FakeSetting(450_000)).read())
    }

    @Test
    fun missingAndNonPositiveReadsStayUnknown() {
        assertEquals(IdleReading.Unknown(IdleReading.Reason.MISSING), IdleTime(FakeSetting(null)).read())
        assertEquals(IdleReading.Unknown(IdleReading.Reason.NOT_POSITIVE), IdleTime(FakeSetting(0)).read())
        assertEquals(IdleReading.Unknown(IdleReading.Reason.NOT_POSITIVE), IdleTime(FakeSetting(-1)).read())
    }

    @Test
    fun anUnreadableSettingIsUnsupportedAndIsLogged() {
        val logged = mutableListOf<String>()
        val time = IdleTime(FakeSetting(readThrows = true)) { message, _ -> logged += message }
        assertEquals(IdleReading.Unsupported, time.read())
        assertEquals(1, logged.size)
    }

    @Test
    fun writeChangesTheSettingAndReadsItBack() {
        val setting = FakeSetting(600_000)
        assertEquals(IdleWrite.Applied(300_000), IdleTime(setting).write(5))
        assertEquals(300_000, setting.stored)
    }

    @Test
    fun readingNeverWrites() {
        val setting = FakeSetting()
        val time = IdleTime(setting)
        time.read()
        time.canWrite()
        assertEquals(0, setting.writes)
    }

    @Test
    fun aValueThatIsNotOneOfTheChoicesIsNeverWritten() {
        val setting = FakeSetting()
        val time = IdleTime(setting)
        assertEquals(IdleWrite.NotAChoice, time.write(7))
        assertEquals(IdleWrite.NotAChoice, time.write(0))
        assertEquals(IdleWrite.NotAChoice, time.write(-5))
        assertEquals(0, setting.writes)
    }

    @Test
    fun withoutPermissionNothingIsWritten() {
        val setting = FakeSetting(writable = false)
        assertEquals(IdleWrite.NeedsPermission, IdleTime(setting).write(5))
        assertEquals(0, setting.writes)
        assertEquals(600_000, setting.stored)
    }

    @Test
    fun permissionRevokedAfterTheCheckIsReportedAsNeedingPermission() {
        val setting = FakeSetting(writeThrows = true)
        assertEquals(IdleWrite.NeedsPermission, IdleTime(setting).write(5))
    }

    @Test
    fun aRefusedWriteReportsWhatTheSystemStillHolds() {
        val setting = FakeSetting(accepts = false)
        assertEquals(IdleWrite.Refused(IdleReading.Known(600_000)), IdleTime(setting).write(5))
    }

    @Test
    fun aWriteThatReadsBackDifferentlyIsAMismatchNotASuccess() {
        val setting = FakeSetting(storesWhatWasWritten = false)
        assertEquals(IdleWrite.Mismatch(IdleReading.Known(600_000)), IdleTime(setting).write(5))
    }

    @Test
    fun anUnsupportedSettingIsNotWritten() {
        val setting = FakeSetting(readThrows = true)
        assertEquals(IdleWrite.Unsupported, IdleTime(setting).write(5))
        assertEquals(0, setting.writes)
    }

    @Test
    fun wholeMinutesAndOddValuesAreLabelledHonestly() {
        assertEquals(10, IdleTime.wholeMinutes(600_000))
        assertNull(IdleTime.wholeMinutes(450_000))
        assertNull(IdleTime.wholeMinutes(0))
        assertEquals(7 to 30, IdleTime.minutesAndSeconds(450_000))
        assertEquals(0 to 45, IdleTime.minutesAndSeconds(45_000))
        assertTrue(IdleTime(FakeSetting()).canWrite())
        assertFalse(IdleTime(FakeSetting(writable = false)).canWrite())
    }
}
