package com.cosmos.screensaver

import android.app.Activity
import android.os.Bundle
import android.provider.Settings
import android.content.Intent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(72, 64, 72, 64)
            setBackgroundColor(0xFF050914.toInt())
        }

        val title = TextView(this).apply {
            text = getString(R.string.home_title)
            textSize = 30f
            setTextColor(0xFFFFFFFF.toInt())
        }
        val description = TextView(this).apply {
            text = getString(R.string.home_description) + "\n\n" +
                getString(R.string.home_instructions)
            textSize = 18f
            setTextColor(0xFFD5E2F5.toInt())
            setPadding(0, 24, 0, 24)
        }
        val settingsButton = Button(this).apply {
            text = "Open screensaver settings"
            setOnClickListener {
                runCatching {
                    startActivity(Intent(Settings.ACTION_DREAM_SETTINGS))
                }
            }
        }

        layout.addView(title)
        layout.addView(description)
        layout.addView(settingsButton)
        setContentView(layout)
    }
}
