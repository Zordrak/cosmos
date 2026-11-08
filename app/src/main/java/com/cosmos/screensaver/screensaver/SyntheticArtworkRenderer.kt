package com.cosmos.screensaver.screensaver

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.random.Random

/**
 * Original procedural starfield, shown while no rights-cleared image is cached (and used by the
 * Milestone 001 display test). It contains no third-party or externally sourced imagery.
 */
internal class SyntheticArtworkRenderer {
    private val stars = List(420) { index ->
        val random = Random(index * 7919 + 17)
        Star(
            x = random.nextFloat() * SyntheticArtworkGeometry.WIDTH,
            y = random.nextFloat() * SyntheticArtworkGeometry.HEIGHT,
            radius = 0.7f + random.nextFloat() * 2.0f,
            alpha = 90 + random.nextInt(166)
        )
    }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = SyntheticArtworkGeometry.MARKER_STROKE_WIDTH
        strokeCap = Paint.Cap.SQUARE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val nebulaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            1010f, 525f, 490f,
            intArrayOf(Color.argb(105, 70, 91, 180), Color.argb(65, 88, 42, 145), Color.TRANSPARENT),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
    }
    private val secondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            1180f, 440f, 260f,
            intArrayOf(Color.argb(80, 42, 135, 180), Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP
        )
    }

    /** Draws the 16:9 composition centred and uncropped; [message] appears near the bottom. */
    fun draw(canvas: Canvas, width: Int, height: Int, message: String?, showTestMarkers: Boolean) {
        canvas.drawColor(Color.rgb(3, 7, 17))
        if (width <= 0 || height <= 0) return
        val viewport = ArtworkViewport.fit(width, height)
        val checkpoint = canvas.save()
        canvas.translate(viewport.left, viewport.top)
        canvas.scale(viewport.scale, viewport.scale)

        canvas.drawCircle(1010f, 525f, 490f, nebulaPaint)
        canvas.drawCircle(1180f, 440f, 260f, secondaryPaint)
        stars.forEach { star ->
            starPaint.color = Color.argb(star.alpha, 225, 237, 255)
            canvas.drawCircle(star.x, star.y, star.radius, starPaint)
        }

        textPaint.alpha = 210
        textPaint.textSize = 25f
        canvas.drawText("COSMOS", 92f, 100f, textPaint)
        if (message != null) {
            textPaint.alpha = 190
            textPaint.textSize = 22f
            canvas.drawText(message, 92f, 1010f, textPaint)
        }
        if (showTestMarkers) {
            SyntheticArtworkGeometry.cornerMarkers.forEach { marker ->
                canvas.drawLine(marker.x, marker.y, marker.horizontalEndX, marker.y, markerPaint)
                canvas.drawLine(marker.x, marker.y, marker.x, marker.verticalEndY, markerPaint)
            }
        }
        canvas.restoreToCount(checkpoint)
    }

    private data class Star(val x: Float, val y: Float, val radius: Float, val alpha: Int)
}
