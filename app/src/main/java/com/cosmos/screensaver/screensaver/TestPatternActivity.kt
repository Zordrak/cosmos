package com.cosmos.screensaver.screensaver

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Bundle
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager

/**
 * A pixel-exact test card for checking that the panel receives a true 4K picture. One-pixel
 * alternating lines stay crisp when the buffer is mapped 1:1 to the panel, and turn into flat
 * grey when it is scaled up from a lower resolution. Launched manually over ADB; any key closes it.
 */
class TestPatternActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(TestPatternView(this))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        finish()
        return true
    }
}

private class TestPatternView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    init {
        holder.addCallback(this)
        holder.setFormat(PixelFormat.RGBX_8888)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val mode = display?.mode
        val width = mode?.let { maxOf(it.physicalWidth, it.physicalHeight) } ?: 0
        val height = mode?.let { minOf(it.physicalWidth, it.physicalHeight) } ?: 0
        if (width in 640..3840 && height in 360..2160) holder.setFixedSize(width, height)
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val canvas = holder.lockCanvas() ?: return
        try {
            draw(canvas, width, height)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun draw(canvas: Canvas, width: Int, height: Int) {
        canvas.drawColor(Color.BLACK)
        val half = width / 2
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val on = if (x < half) y % 2 == 0 else x % 2 == 0
                pixels[y * width + x] = if (on) Color.WHITE else Color.BLACK
            }
        }
        canvas.drawBitmap(Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888), 0f, 0f, null)

        val box = Paint().apply { color = Color.BLACK }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = height / 24f
            textAlign = Paint.Align.CENTER
        }
        val mode = display?.mode
        val lines = listOf(
            "Cosmos 4K test pattern",
            "Surface buffer: ${width} x $height",
            "Display mode: ${mode?.physicalWidth} x ${mode?.physicalHeight}",
            "Crisp 1-pixel lines = true pixel-for-pixel output; flat grey = scaled up",
            "Press any key to close"
        )
        val lineHeight = text.textSize * 1.5f
        val boxWidth = width * 0.7f
        val boxHeight = lineHeight * (lines.size + 1)
        val left = (width - boxWidth) / 2
        val top = (height - boxHeight) / 2
        canvas.drawRect(left, top, left + boxWidth, top + boxHeight, box)
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, width / 2f, top + lineHeight * (index + 1), text)
        }
    }
}
