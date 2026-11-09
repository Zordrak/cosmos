package com.cosmos.screensaver.ui

import com.cosmos.screensaver.settings.CosmosSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SizeSessionTest {
    private val choices = CosmosSettings.CACHE_LIMIT_CHOICES

    @Test
    fun movingThroughSizesOnlyChangesTheProposal() {
        val session = SizeSession(choices[2])
        assertFalse(session.hasProposal)
        assertTrue(session.step(+1))
        assertEquals(choices[3], session.candidate)
        assertEquals(choices[2], session.committed)
        assertTrue(session.hasProposal)
    }

    @Test
    fun discardingTheProposalKeepsTheSizeInForce() {
        val session = SizeSession(choices[2])
        session.propose(choices[1])
        session.discard()
        assertEquals(choices[2], session.candidate)
        assertEquals(choices[2], session.committed)
        assertFalse(session.hasProposal)
    }

    @Test
    fun aSizeThatIsNotOfferedIsRefused() {
        val session = SizeSession(choices[2])
        assertFalse(session.propose(12345L))
        assertEquals(choices[2], session.candidate)
    }

    @Test
    fun appliedAndResyncMakeTheProposalTheNewBaseline() {
        val session = SizeSession(choices[2])
        session.propose(choices[4])
        session.applied(choices[4])
        assertEquals(choices[4], session.committed)
        assertFalse(session.hasProposal)
        session.propose(choices[1])
        session.resync(choices[2])
        assertEquals(choices[2], session.committed)
        assertEquals(choices[2], session.candidate)
    }

    @Test
    fun steppingWrapsLikeEveryOtherChoiceRow() {
        val session = SizeSession(choices.last())
        session.step(+1)
        assertEquals(choices.first(), session.candidate)
    }
}
