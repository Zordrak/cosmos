package com.cosmos.screensaver.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PickerModelTest {
    private fun options(vararg disabled: Int) =
        (0 until 4).map { PickerOption(it, "o$it", disabledReason = if (it in disabled) "no" else null) }

    @Test
    fun `initial index is the current option, else the first, else none`() {
        assertEquals(2, PickerModel.initialIndex(options(), 2))
        assertEquals(0, PickerModel.initialIndex(options(), 99))
        assertEquals(-1, PickerModel.initialIndex(emptyList<PickerOption<Int>>(), 1))
    }

    @Test
    fun `disabled options are not choosable`() {
        val o = options(1)
        assertTrue(PickerModel.isChoosable(o, 0))
        assertFalse(PickerModel.isChoosable(o, 1))
        assertFalse(PickerModel.isChoosable(o, 9))
        assertFalse(PickerModel.isChoosable(o, -1))
    }

    @Test
    fun `cycle wraps both ways`() {
        assertEquals(0, PickerModel.cycle(listOf(0, 1, 2), 2, 1))
        assertEquals(2, PickerModel.cycle(listOf(0, 1, 2), 0, -1))
    }

    @Test
    fun `cycle with nothing to move to returns null`() {
        assertNull(PickerModel.cycle(emptyList<Int>(), 1, 1))
        assertNull(PickerModel.cycle(listOf(5), 5, 1))
    }

    @Test
    fun `cycle from a value outside the list enters at the near end`() {
        assertEquals(0, PickerModel.cycle(listOf(0, 1, 2), 99, 1))
        assertEquals(2, PickerModel.cycle(listOf(0, 1, 2), 99, -1))
    }
}
