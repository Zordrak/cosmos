package com.cosmos.screensaver.screensaver

import com.cosmos.screensaver.catalogue.CachedOrder
import com.cosmos.screensaver.catalogue.CatalogueEntry
import com.cosmos.screensaver.catalogue.CatalogueStoreTest
import com.cosmos.screensaver.catalogue.UserState
import com.cosmos.screensaver.rights.AssetProvider
import com.cosmos.screensaver.settings.ImageSelection
import com.cosmos.screensaver.settings.OrderMode
import com.cosmos.screensaver.settings.QualityPreference
import com.cosmos.screensaver.settings.SelectionSnapshot
import com.cosmos.screensaver.settings.SourceWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.random.Random

class ImagePickerTest {
    private fun pic(id: String, weight: SourceWeight = SourceWeight.NORMAL, favourite: Boolean = false, admitted: Long = 0L, title: String = "t$id", provider: String = "ESA_HUBBLE") =
        PickCandidate(id, provider, title, weight, favourite, admitted)

    /** Plays the controller's role: tracks history and carries the order state from pick to pick. */
    private class Sim(var pool: List<PickCandidate>, val mode: OrderMode = OrderMode.FAIR, seed: Int = 7) {
        val random = Random(seed)
        var state = CachedOrder()
        var last: String? = null
        private var ordinal = 0L
        private val counts = mutableMapOf<String, Long>()
        private val ordinals = mutableMapOf<String, Long>()
        val history = mutableListOf<String>()

        fun count(id: String) = counts[id] ?: 0L

        fun step(excluded: Set<String> = emptySet(), library: Set<String>? = null): OrderPick? {
            val candidates = pool.map { it.copy(shownCount = count(it.hash), lastShownOrdinal = ordinals[it.hash] ?: 0L) }
            val pick = ImagePicker.pick(mode, state, candidates, library ?: pool.map { it.hash }.toSet(), last, excluded, random) ?: return null
            state = pick.next
            ordinal++
            counts[pick.hash] = count(pick.hash) + 1
            ordinals[pick.hash] = ordinal
            last = pick.hash
            history += pick.hash
            return pick
        }

        fun run(times: Int) = repeat(times) { step() }
    }

    private fun twenty() = (1..20).map { pic("p%02d".format(it)) }

    @Test
    fun aStablePoolShowsEveryPictureOnceBeforeAnyRepeat() {
        val sim = Sim(twenty())
        sim.run(20)
        assertEquals(20, sim.history.toSet().size)
    }

    @Test
    fun aStablePoolNeverRepeatsBackToBackAndStaysEven() {
        val sim = Sim(twenty())
        sim.run(2000)
        sim.history.zipWithNext().forEach { (a, b) -> assertNotEquals(a, b) }
        sim.pool.forEach { assertEquals(100L, sim.count(it.hash)) }
    }

    @Test
    fun emptyOneAndTwoPicturePoolsTerminate() {
        assertNull(Sim(emptyList()).step())
        val one = Sim(listOf(pic("a")))
        one.run(5)
        assertEquals(List(5) { "a" }, one.history)
        val two = Sim(listOf(pic("a"), pic("b")))
        two.run(10)
        two.history.zipWithNext().forEach { (a, b) -> assertNotEquals(a, b) }
    }

    @Test
    fun aNewArrivalIsShownWithinFivePicks() {
        val sim = Sim(twenty())
        sim.run(100)
        sim.pool = sim.pool + pic("new", admitted = 5)
        val before = sim.history.size
        repeat(5) { sim.step() }
        assertTrue(sim.history.drop(before).contains("new"))
        assertEquals(1L, sim.count("new"))
    }

    @Test
    fun aNewBatchIsServedOldestFirstAndOnlyOnce() {
        val sim = Sim(twenty())
        sim.run(40)
        sim.pool = sim.pool + listOf(pic("n3", admitted = 3), pic("n1", admitted = 1), pic("n2", admitted = 2))
        val before = sim.history.size
        sim.run(30)
        val after = sim.history.drop(before)
        val firsts = after.filter { it.startsWith("n") }.distinct()
        assertEquals(listOf("n1", "n2", "n3"), firsts)
        assertEquals(3, after.take(15).count { it.startsWith("n") })
    }

    @Test
    fun constantArrivalsNeverStarveTheOrdinaryPool() {
        val sim = Sim(twenty())
        sim.run(40)
        var arrivals = 0
        var priority = 0
        val before = sim.history.size
        repeat(40) {
            sim.pool = sim.pool + pic("x${arrivals++}", admitted = arrivals.toLong())
            if (sim.step()!!.priorityNew) priority++
        }
        // At most one priority pick per four ordinary ones (plus the opening one).
        assertTrue("priority=$priority", priority in 1..9)
        assertTrue(sim.history.drop(before).count { it.startsWith("p") } >= 20)
    }

    @Test
    fun aPictureThatFailsIsSkippedWithoutBlockingThePool() {
        val sim = Sim(listOf(pic("a"), pic("b"), pic("c")))
        repeat(30) { assertNotEquals("b", sim.step(excluded = setOf("b"))!!.hash) }
        assertNull(sim.step(excluded = setOf("a", "b", "c")))
    }

    @Test
    fun aBrokenPictureDoesNotDistortTheOthersScores() {
        val sim = Sim(twenty())
        sim.run(20)
        repeat(200) { sim.step(excluded = setOf("p01")) }
        assertTrue(sim.state.fair.scores.values.all { it in 0..ImagePicker.MAX_SCORE })
        sim.pool.filter { it.hash != "p01" }.map { sim.count(it.hash) }.let { counts ->
            assertTrue(counts.max() - counts.min() <= 1)
        }
    }

    @Test
    fun moreAndLessWeightsShiftFrequencyAsTwoToOne() {
        val sim = Sim((1..5).map { pic("n$it") } + (1..5).map { pic("m$it", SourceWeight.MORE) } + (1..5).map { pic("l$it", SourceWeight.LESS) })
        sim.run(6000)
        val normal = (1..5).sumOf { sim.count("n$it") }.toDouble()
        val more = (1..5).sumOf { sim.count("m$it") }.toDouble()
        val less = (1..5).sumOf { sim.count("l$it") }.toDouble()
        assertEquals(2.0, more / normal, 0.15)
        assertEquals(0.5, less / normal, 0.1)
    }

    @Test
    fun favouritesAreShownRoughlyTwiceAsOften() {
        val sim = Sim((1..10).map { pic("p$it", favourite = it <= 3) })
        sim.run(5000)
        val fav = (1..3).sumOf { sim.count("p$it") } / 3.0
        val other = (4..10).sumOf { sim.count("p$it") } / 7.0
        assertEquals(2.0, fav / other, 0.2)
    }

    @Test
    fun aTwoPicturePoolWithDifferentWeightsKeepsBoundedScores() {
        val sim = Sim(listOf(pic("a", SourceWeight.LESS), pic("b", SourceWeight.MORE)))
        repeat(500) {
            sim.step()
            assertTrue(sim.state.fair.scores.values.all { it in 0..ImagePicker.MAX_SCORE })
        }
        sim.history.zipWithNext().forEach { (x, y) -> assertNotEquals(x, y) }
    }

    @Test
    fun allFavouritesBehaveLikeAnEvenPool() {
        val sim = Sim((1..10).map { pic("p$it", favourite = true) })
        sim.run(1000)
        (1..10).forEach { assertEquals(100L, sim.count("p$it")) }
    }

    @Test
    fun aPictureThatLeavesAndReturnsRejoinsAtTheFloorWithoutBeingTreatedAsNew() {
        val sim = Sim(twenty())
        sim.run(60)
        val removed = sim.pool.first { it.hash == "p05" }
        sim.pool = sim.pool - removed
        sim.run(60)
        sim.pool = sim.pool + removed
        val before = sim.history.size
        sim.run(10)
        // Rejoining at the floor means it is not priority-served ahead of its turn, and is still shown soon.
        val position = sim.history.drop(before).indexOf("p05")
        assertTrue(position in 0..20 || position == -1)
        assertFalse(sim.state.fair.pendingNew.contains("p05"))
    }

    @Test
    fun theFirstPickAfterAnyChangeNeverRepeatsTheLastShownPicture() {
        val sim = Sim(listOf(pic("a"), pic("b")))
        sim.step()
        val last = sim.last
        sim.pool = listOf(pic("a"), pic("b"), pic("c"))
        assertNotEquals(last, sim.step()!!.hash)
    }

    @Test
    fun randomModeIgnoresWeightsAndLeavesTheFairStateUntouched() {
        val sim = Sim(listOf(pic("a", SourceWeight.MORE), pic("b", SourceWeight.LESS), pic("c")), OrderMode.RANDOM)
        sim.run(3000)
        sim.history.zipWithNext().forEach { (x, y) -> assertNotEquals(x, y) }
        assertEquals(CachedOrder(), sim.state)
        listOf("a", "b", "c").forEach { assertTrue(sim.count(it) in 800L..1200L) }
    }

    @Test
    fun inOrderWalksTheSameOrderForeverAndWraps() {
        val pool = listOf(pic("3", title = "Beta"), pic("1", title = "alpha"), pic("2", title = "Beta"), pic("4", provider = "NASA_APOD", title = "Zeta"))
        val sim = Sim(pool, OrderMode.SEQUENTIAL)
        sim.run(8)
        assertEquals(listOf("1", "2", "3", "4"), sim.history.take(4))
        assertEquals(sim.history.take(4), sim.history.drop(4))
    }

    @Test
    fun inOrderResumesFromTheCursorWhenPicturesAreAddedOrRemoved() {
        val sim = Sim((1..5).map { pic("p$it", title = "p$it") }, OrderMode.SEQUENTIAL)
        sim.run(2)
        sim.pool = sim.pool.filter { it.hash != "p3" } + pic("p2b", title = "p2b")
        sim.step()
        assertEquals("p2b", sim.history.last())
        sim.step()
        assertEquals("p4", sim.history.last())
    }

    @Test
    fun inOrderIsStableUnderTheTurkishLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            val sim = Sim(listOf(pic("a", title = "ICE"), pic("b", title = "ice nine"), pic("c", title = "Ice")), OrderMode.SEQUENTIAL)
            sim.run(6)
            assertEquals(sim.history.take(3), sim.history.drop(3))
            assertEquals(3, sim.history.toSet().size)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun inOrderSkipsTheLastShownPictureWhenAnotherExists() {
        val sim = Sim(listOf(pic("a"), pic("b")), OrderMode.SEQUENTIAL)
        sim.last = "a"
        assertEquals("b", sim.step()!!.hash)
    }

    @Test
    fun costsAreOneTwoFourEight() {
        assertEquals(2, ImagePicker.cost(SourceWeight.MORE, false))
        assertEquals(4, ImagePicker.cost(SourceWeight.NORMAL, false))
        assertEquals(8, ImagePicker.cost(SourceWeight.LESS, false))
        assertEquals(1, ImagePicker.cost(SourceWeight.MORE, true))
        assertEquals(2, ImagePicker.cost(SourceWeight.NORMAL, true))
        assertEquals(4, ImagePicker.cost(SourceWeight.LESS, true))
    }

    private fun entry(id: String, provider: AssetProvider, sha: String): CatalogueEntry =
        CatalogueStoreTest.entry(id).copy(provider = provider, sha256 = sha, fileName = "$sha.jpg")

    private val all = AssetProvider.entries.toSet()
    private val selection = ImageSelection(quality = QualityPreference.ANY)

    @Test
    fun aliasesUseTheStrongestWeightNeverTheSum() {
        val sha = "d".repeat(64)
        val entries = listOf(entry("h", AssetProvider.ESA_HUBBLE, sha), entry("w", AssetProvider.ESA_WEBB, sha))
        val snapshot = SelectionSnapshot.build(entries, selection, all, UserState())
        val candidates = ImagePicker.candidates(snapshot, UserState(), {
            if (it == AssetProvider.ESA_WEBB) SourceWeight.MORE else SourceWeight.LESS
        })
        assertEquals(1, candidates.size)
        assertEquals(SourceWeight.MORE, candidates.single().weight)
    }

    @Test
    fun candidatesCarryFavouriteHistoryAndSkipDamagedKeys() {
        val a = entry("a", AssetProvider.ESA_HUBBLE, "a".repeat(64))
        val b = entry("b", AssetProvider.ESA_HUBBLE, "b".repeat(64))
        val state = UserState(
            favourites = setOf(a.sha256),
            stats = mapOf(a.sha256 to com.cosmos.screensaver.catalogue.PictureStats(3, 9L, 12L))
        )
        val snapshot = SelectionSnapshot.build(listOf(a, b), selection, all, state)
        val candidates = ImagePicker.candidates(snapshot, state, { SourceWeight.NORMAL }, skipKeys = setOf(b.key))
        val only = candidates.single()
        assertEquals(a.sha256, only.hash)
        assertTrue(only.favourite)
        assertEquals(3L, only.shownCount)
        assertEquals(12L, only.lastShownOrdinal)
    }
}
