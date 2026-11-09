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
import com.cosmos.screensaver.R

/**
 * A specific, Cancel-first confirmation. The message must say exactly what will be affected; the
 * confirm button is styled as a warning. Back or Cancel changes nothing.
 */
internal object ConfirmDialog {
    fun show(
        activity: Activity,
        title: String,
        message: String,
        confirmLabel: String,
        onConfirm: () -> Unit
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
            setPadding(ui.dp(40), ui.dp(32), ui.dp(40), ui.dp(28))
        }
        panel.addView(ui.text(title, SettingsType.PAGE_TITLE, Color.WHITE, bold = true))
        panel.addView(ui.text(message, SettingsType.ROW_TITLE, SettingsColors.SECONDARY).apply {
            setPadding(0, ui.dp(16), 0, ui.dp(28))
        })

        val buttons = LinearLayout(activity).apply { gravity = Gravity.END }
        val cancel = button(ui, activity.getString(R.string.confirm_cancel), SettingsColors.ACCENT)
        val confirm = button(ui, confirmLabel, SettingsColors.WARNING)
        cancel.setOnClickListener { dialog.dismiss() }
        confirm.setOnClickListener {
            dialog.dismiss()
            onConfirm()
        }
        buttons.addView(cancel)
        buttons.addView(confirm, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = ui.dp(16) })
        panel.addView(buttons)

        val frame = LinearLayout(activity).apply {
            gravity = Gravity.CENTER
            addView(panel, LinearLayout.LayoutParams(ui.dp(820), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        dialog.setContentView(frame)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0xE6000000.toInt()))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        dialog.show()
        cancel.requestFocus()
    }

    private fun button(ui: SettingsUi, label: String, colour: Int): View =
        ui.text(label, SettingsType.ROW_TITLE, colour, bold = true).apply {
            isFocusable = true
            isClickable = true
            gravity = Gravity.CENTER
            minimumWidth = ui.dp(160)
            minimumHeight = ui.dp(56)
            setPadding(ui.dp(24), ui.dp(12), ui.dp(24), ui.dp(12))
            background = ui.rowBackground()
        }
}
