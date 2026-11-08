package com.cosmos.screensaver.screensaver

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.service.dreams.DreamService
import android.util.Log
import android.view.View
import kotlin.random.Random

class CosmosDreamService : DreamService() {
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        // A non-interactive dream is dismissed by the platform on first user input.
        isInteractive = false
        isFullscreen = true
        setContentView(CosmosTestArtworkView(this))
        Log.i(TAG, "Dream window attached")
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        Log.i(TAG, "Dream started")
    }

    override fun onDreamingStopped() {
        Log.i(TAG, "Dream stopped")
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        Log.i(TAG, "Dream window detached")
        super.onDetachedFromWindow()
    }

    companion object {
        private const val TAG = "CosmosDream"
    }
}

/**
 * Original procedural artwork used only to prove the native screensaver rendering path.
 * It contains no third-party or externally sourced imagery.
 */
private class CosmosTestArtworkView(context: Context) : View(context) {
    private val stars = List(420) { index ->
        val random = Random(index * 7919 + 17)
        Star(
            x = random.nextFloat() * ART_WIDTH,
            y = random.nextFloat() * ART_HEIGHT,
            radius = 0.7f + random.nextFloat() * 2.0f,
            alpha = 90 + random.nextInt(166)
        )
    }

    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(3, 7, 17))

        // Render a fixed 16:9 composition, centred and never stretched.
        if (width <= 0 || height <= 0) return
        val viewport = ArtworkViewport.fit(width, height)

        val checkpoint = canvas.save()
        canvas.translate(viewport.left, viewport.top)
        canvas.scale(viewport.scale, viewport.scale)

        drawNebula(canvas)
        drawStars(canvas)
        drawTestLabel(canvas)

        canvas.restoreToCount(checkpoint)
    }

    private fun drawNebula(canvas: Canvas) {
        val nebulaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                1010f, 525f, 490f,
                intArrayOf(
                    Color.argb(105, 70, 91, 180),
                    Color.argb(65, 88, 42, 145),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(1010f, 525f, 490f, nebulaPaint)

        val secondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                1180f, 440f, 260f,
                intArrayOf(Color.argb(80, 42, 135, 180), Color.TRANSPARENT),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(1180f, 440f, 260f, secondaryPaint)
    }

    private fun drawStars(canvas: Canvas) {
        stars.forEach { star ->
            starPaint.color = Color.argb(star.alpha, 225, 237, 255)
            canvas.drawCircle(star.x, star.y, star.radius, starPaint)
        }
    }

    private fun drawTestLabel(canvas: Canvas) {
        textPaint.alpha = 210
        textPaint.textSize = 25f
        canvas.drawText("COSMOS  /  MILESTONE 001", 92f, 100f, textPaint)

        textPaint.alpha = 145
        textPaint.textSize = 17f
        canvas.drawText("NATIVE DREAMSERVICE DISPLAY TEST", 94f, 132f, textPaint)

        textPaint.alpha = 170
        textPaint.textSize = 18f
        canvas.drawText("SYNTHETIC ARTWORK  -  NO NETWORK REQUIRED", 92f, 1010f, textPaint)
    }

    private data class Star(
        val x: Float,
        val y: Float,
        val radius: Float,
        val alpha: Int
    )

    companion object {
        private const val ART_WIDTH = 1920f
        private const val ART_HEIGHT = 1080f
    }
}
