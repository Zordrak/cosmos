package com.cosmos.screensaver.ui

import com.cosmos.screensaver.settings.SetupIssue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SetupNoticesTest {
    @Test
    fun `every issue maps to a fix or deliberately none`() {
        SetupIssue.entries.forEach { SetupNotices.fixFor(it) }
        assertNull(SetupNotices.fixFor(SetupIssue.UNREADABLE))
    }

    @Test
    fun `empty choices lead to the choices, not a guess`() {
        assertEquals(SetupFix.REVIEW_CHOICES, SetupNotices.fixFor(SetupIssue.NO_SOURCES))
        assertEquals(SetupFix.REVIEW_CHOICES, SetupNotices.fixFor(SetupIssue.NO_MATCHING_PICTURES))
    }

    @Test
    fun `space problems lead to the size page and contact problems retry`() {
        assertEquals(SetupFix.REVIEW_SIZE, SetupNotices.fixFor(SetupIssue.LOW_SPACE))
        assertEquals(SetupFix.REVIEW_SIZE, SetupNotices.fixFor(SetupIssue.FAVOURITES_FILL_SPACE))
        assertEquals(SetupFix.TRY_AGAIN, SetupNotices.fixFor(SetupIssue.CANNOT_REACH_SOURCES))
    }

    @Test
    fun `every screensaver problem leads to the TV settings`() {
        listOf(SetupIssue.SCREENSAVER_NOT_SELECTED, SetupIssue.SCREENSAVER_OTHER, SetupIssue.SCREENSAVER_DISABLED)
            .forEach { assertEquals(SetupFix.CHOOSE_SCREENSAVER, SetupNotices.fixFor(it)) }
    }
}
