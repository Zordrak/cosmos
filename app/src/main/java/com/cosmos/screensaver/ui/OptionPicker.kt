package com.cosmos.screensaver.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cosmos.screensaver.R

/** Pure picker behaviour, separated from the views so it can be unit-tested. */
internal object PickerModel {
    /** Index to focus first: the current option, else the first one, else -1 for an empty list. */
    fun <T> initialIndex(options: List<PickerOption<T>>, current: T): Int {
        if (options.isEmpty()) return -1
        return options.indexOfFirst { it.value == current }.takeIf { it >= 0 } ?: 0
    }

    /** True when choosing the option at [index] should do something. Disabled options are inert. */
    fun <T> isChoosable(options: List<PickerOption<T>>, index: Int): Boolean =
        index in options.indices && options[index].disabledReason == null

    /**
     * The value [direction] places after [current] among [values], wrapping around. Returns null
     * when there is nothing to move to (empty list, or [current] is the only value), so callers
     * never loop or report a change that did not happen.
     */
    fun <T> cycle(values: List<T>, current: T, direction: Int): T? {
        if (values.isEmpty()) return null
        val index = values.indexOf(current)
        val start = if (index >= 0) index else if (direction > 0) -1 else 0
        val next = Math.floorMod(start + direction, values.size)
        val target = values[next]
        return if (target == current) null else target
    }
}

/** An app-styled single-choice list: a title, then one row per option with a description. */
internal object OptionPicker {
    fun <T> show(
        activity: Activity,
        title: String,
        options: List<PickerOption<T>>,
        current: T,
        onChosen: (T) -> Unit
    ) {
        val ui = SettingsUi(activity)
        val dialog = Dialog(activity, R.style.CosmosDialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(SettingsColors.PANEL)
                cornerRadius = ui.dp(16).toFloat()
            }
            setPadding(ui.dp(32), ui.dp(24), ui.dp(32), ui.dp(24))
        }
        panel.addView(ui.text(title, SettingsType.PAGE_TITLE, Color.WHITE, bold = true).apply {
            setPadding(ui.dp(8), 0, 0, ui.dp(16))
        })

        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val rowViews = options.mapIndexed { index, option ->
            optionRow(ui, activity, option, selected = option.value == current).also { row ->
                row.setOnClickListener {
                    if (PickerModel.isChoosable(options, index)) {
                        dialog.dismiss()
                        onChosen(option.value)
                    }
                }
                list.addView(row, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = ui.dp(4) })
            }
        }
        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            addView(list)
        }
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val frame = LinearLayout(activity).apply {
            gravity = Gravity.CENTER
            setPadding(ui.dp(120), ui.dp(40), ui.dp(120), ui.dp(40))
            addView(panel, LinearLayout.LayoutParams(ui.dp(760), ViewGroup.LayoutParams.MATCH_PARENT))
        }
        dialog.setContentView(frame)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0xE6000000.toInt()))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        dialog.show()
        PickerModel.initialIndex(options, current).takeIf { it >= 0 }?.let { index ->
            rowViews[index].requestFocus()
            val target = rowViews[index]
            scroll.post { scroll.scrollTo(0, (target.top - ui.dp(48)).coerceAtLeast(0)) }
        }
    }

    private fun <T> optionRow(
        ui: SettingsUi,
        activity: Activity,
        option: PickerOption<T>,
        selected: Boolean
    ): View {
        val disabled = option.disabledReason != null
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            background = ui.rowBackground()
            setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
        }
        row.addView(ui.text(if (selected) "\u2713" else "", SettingsType.ROW_TITLE, SettingsColors.ACCENT, bold = true).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ui.dp(36), ViewGroup.LayoutParams.WRAP_CONTENT))

        val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val labelLine = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        labelLine.addView(ui.text(option.label, SettingsType.ROW_TITLE, if (disabled) SettingsColors.MUTED else Color.WHITE))
        if (option.recommended) labelLine.addView(chip(ui, activity.getString(R.string.picker_recommended)))
        texts.addView(labelLine)
        val detail = option.disabledReason ?: option.description
        if (detail.isNotBlank()) {
            texts.addView(
                ui.text(detail, SettingsType.SUMMARY, if (disabled) SettingsColors.WARNING else SettingsColors.SECONDARY)
                    .apply { setPadding(0, ui.dp(2), 0, 0) }
            )
        }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.contentDescription = listOfNotNull(
            option.label,
            if (selected) activity.getString(R.string.picker_selected) else null,
            if (option.recommended) activity.getString(R.string.picker_recommended) else null,
            detail.takeIf { it.isNotBlank() }
        ).joinToString(". ")
        return row
    }

    private fun chip(ui: SettingsUi, label: String): TextView =
        ui.text(label, SettingsType.SUMMARY, SettingsColors.ACCENT, bold = true).apply {
            background = GradientDrawable().apply {
                setColor(0x331A8CFF)
                cornerRadius = ui.dp(12).toFloat()
            }
            setPadding(ui.dp(10), ui.dp(2), ui.dp(10), ui.dp(2))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = ui.dp(14) }
        }
}
