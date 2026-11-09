package com.cosmos.screensaver.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Colours shared by every settings surface. Contrast on [BACKGROUND] is at least 4.5:1 for all text colours. */
internal object SettingsColors {
    const val BACKGROUND = 0xFF070B16.toInt()
    const val PANEL = 0xFF0E1526.toInt()
    const val FOCUSED = 0x26FFFFFF
    const val ACCENT = 0xFF8CC8FF.toInt()
    const val SECONDARY = 0xFFAFC0D8.toInt()
    const val MUTED = 0xFF8393AB.toInt()
    const val WARNING = 0xFFFFB86B.toInt()
}

/** Type scale for viewing from a sofa (design section 2, principle 14). Sizes are sp. */
internal object SettingsType {
    const val PAGE_TITLE = 34f
    const val ROW_TITLE = 22f
    const val ROW_VALUE = 20f
    const val SUMMARY = 16f
    const val LEFT_PANE = 16f
    const val HOME_TITLE = 40f
}

/** A focusable settings row. [update] recomputes its texts in place, so polling never rebuilds the focus tree. */
internal class SettingsRow(val id: String, val view: View, private val onUpdate: () -> Unit) {
    fun update() = onUpdate()
}

/** One entry of a value row's picker. [disabledReason] dims the option and replaces its description. */
internal data class PickerOption<T>(
    val value: T,
    val label: String,
    val description: String = "",
    val recommended: Boolean = false,
    val disabledReason: String? = null
)

/**
 * Builds the TV-friendly rows used on every settings page. Each row has a stable [SettingsRow.id];
 * Select acts once (on key-up, via the click listener) and Left/Right act on key-down only.
 */
internal class SettingsUi(private val activity: Activity) {
    private val density = activity.resources.displayMetrics.density
    private val registered = mutableListOf<SettingsRow>()
    val rows: List<SettingsRow> get() = registered

    fun dp(value: Int) = (value * density).toInt()

    fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(activity).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    /** A row that does something when selected. [value] is an optional right-hand value; null hides it. */
    fun action(
        id: String,
        title: String,
        summary: () -> String,
        value: (() -> String?)? = null,
        enabledReason: () -> String? = { null },
        onUnavailable: (() -> Unit)? = null,
        onSelect: () -> Unit
    ): SettingsRow = row(
        id, title, summary, value, enabledReason,
        onSelect = onSelect, onStep = null, onUnavailable = onUnavailable, stepIsSet = false
    )

    /** A row showing a current value; Select opens [options] in a picker and Left/Right cycle [cycle]. */
    fun <T> choice(
        id: String,
        title: String,
        options: () -> List<PickerOption<T>>,
        current: () -> T,
        onChosen: (T) -> Unit,
        summary: (T) -> String,
        valueLabel: (T) -> String,
        cycle: () -> List<T> = { options().filter { it.disabledReason == null }.map { it.value } },
        enabledReason: () -> String? = { null },
        onUnavailable: (() -> Unit)? = null
    ): SettingsRow {
        var created: SettingsRow? = null
        val open = {
            OptionPicker.show(activity, title, options(), current()) { chosen ->
                onChosen(chosen)
                updateAll()
                created?.view?.requestFocus()
            }
        }
        val step = { direction: Int ->
            PickerModel.cycle(cycle(), current(), direction)?.let {
                onChosen(it)
                updateAll()
            }
            Unit
        }
        return row(
            id, title, { summary(current()) }, { valueLabel(current()) },
            enabledReason, onSelect = open, onStep = step, onUnavailable = onUnavailable, stepIsSet = false
        ).also { created = it }
    }

    /** An On/Off row: Select toggles, Left sets Off and Right sets On, so key repeat cannot oscillate it. */
    fun switch(
        id: String,
        title: String,
        summary: (Boolean) -> String,
        get: () -> Boolean,
        set: (Boolean) -> Unit,
        enabledReason: () -> String? = { null },
        onUnavailable: (() -> Unit)? = null
    ): SettingsRow {
        val apply = { value: Boolean ->
            if (value != get()) set(value)
            updateAll()
        }
        return row(
            id, title, { summary(get()) },
            { activity.getString(if (get()) com.cosmos.screensaver.R.string.value_on else com.cosmos.screensaver.R.string.value_off) },
            enabledReason,
            onSelect = { apply(!get()) },
            onStep = { direction -> apply(direction > 0) },
            onUnavailable = onUnavailable,
            stepIsSet = true
        )
    }

    private fun updateAll() = registered.forEach { it.update() }

    private fun row(
        id: String,
        title: String,
        summary: () -> String,
        value: (() -> String?)?,
        enabledReason: () -> String?,
        onSelect: () -> Unit,
        onStep: ((Int) -> Unit)?,
        onUnavailable: (() -> Unit)?,
        stepIsSet: Boolean
    ): SettingsRow {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            background = rowBackground()
            minimumHeight = dp(72)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val titleView = text(title, SettingsType.ROW_TITLE, Color.WHITE)
        val summaryView = text("", SettingsType.SUMMARY, SettingsColors.SECONDARY).apply { setPadding(0, dp(2), 0, 0) }
        texts.addView(titleView)
        texts.addView(summaryView)
        container.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val valueView = value?.let {
            text("", SettingsType.ROW_VALUE, SettingsColors.ACCENT, bold = true).apply {
                gravity = Gravity.END
                setPadding(dp(16), 0, 0, 0)
            }.also(container::addView)
        }

        container.setOnClickListener {
            if (enabledReason() == null) onSelect() else onUnavailable?.invoke()
        }
        if (onStep != null) {
            container.setOnKeyListener { _, keyCode, event ->
                val direction = when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                    KeyEvent.KEYCODE_DPAD_LEFT -> -1
                    else -> return@setOnKeyListener false
                }
                // Holding a cycling row would race through every value, so only the first press acts.
                if (event.action == KeyEvent.ACTION_DOWN && enabledReason() == null &&
                    (stepIsSet || event.repeatCount == 0)
                ) {
                    onStep(direction)
                }
                true
            }
        }

        val refresh = {
            val reason = enabledReason()
            val shownSummary = reason ?: summary()
            summaryView.text = shownSummary
            summaryView.setTextColor(if (reason != null) SettingsColors.WARNING else SettingsColors.SECONDARY)
            titleView.setTextColor(if (reason != null) SettingsColors.MUTED else Color.WHITE)
            val shownValue = value?.invoke()
            valueView?.apply {
                text = if (shownValue != null && container.isFocused && reason == null && onStep != null) {
                    "\u2039  $shownValue  \u203a"
                } else {
                    shownValue.orEmpty()
                }
                setTextColor(if (reason != null) SettingsColors.MUTED else SettingsColors.ACCENT)
            }
            container.contentDescription = listOfNotNull(title, shownValue, shownSummary).joinToString(". ")
        }
        container.setOnFocusChangeListener { _, _ -> refresh() }
        val created = SettingsRow(id, container, refresh)
        registered += created
        refresh()
        container.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(4) }
        return created
    }

    /** Focus is a fill plus a 2dp accent outline, so it never relies on colour alone. */
    fun rowBackground() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
            setColor(SettingsColors.FOCUSED)
            setStroke(dp(2), SettingsColors.ACCENT)
            cornerRadius = dp(10).toFloat()
        })
        addState(intArrayOf(), GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(dp(2), Color.TRANSPARENT)
            cornerRadius = dp(10).toFloat()
        })
    }
}
