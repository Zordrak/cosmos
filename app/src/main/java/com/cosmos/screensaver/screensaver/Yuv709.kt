package com.cosmos.screensaver.screensaver

/**
 * ARGB to BT.709 limited-range Y'CbCr 4:2:0, the format the video plane expects. Each chroma
 * sample is taken from the average of its 2x2 block. Integer arithmetic only.
 */
internal object Yuv709 {
    class Planes(val width: Int, val height: Int) {
        val y = ByteArray(width * height)
        val cb = ByteArray(width * height / 4)
        val cr = ByteArray(width * height / 4)
    }

    /**
     * Converts two rows of ARGB pixels ([top] and [bottom], each [width] long) into row pair
     * [pairIndex] of [planes]. [width] must be even.
     */
    fun convertRowPair(top: IntArray, bottom: IntArray, width: Int, pairIndex: Int, planes: Planes) {
        val yTop = pairIndex * 2 * width
        val yBottom = yTop + width
        val c = pairIndex * (width / 2)
        var x = 0
        while (x < width) {
            val p00 = top[x]
            val p01 = top[x + 1]
            val p10 = bottom[x]
            val p11 = bottom[x + 1]
            planes.y[yTop + x] = luma(p00)
            planes.y[yTop + x + 1] = luma(p01)
            planes.y[yBottom + x] = luma(p10)
            planes.y[yBottom + x + 1] = luma(p11)
            val r = (red(p00) + red(p01) + red(p10) + red(p11) + 2) shr 2
            val g = (green(p00) + green(p01) + green(p10) + green(p11) + 2) shr 2
            val b = (blue(p00) + blue(p01) + blue(p10) + blue(p11) + 2) shr 2
            val i = c + x / 2
            planes.cb[i] = clamp(128 + ((CB_R * r + CB_G * g + CB_B * b + HALF) shr 16), 16, 240).toByte()
            planes.cr[i] = clamp(128 + ((CR_R * r + CR_G * g + CR_B * b + HALF) shr 16), 16, 240).toByte()
            x += 2
        }
    }

    fun luma(argb: Int): Byte =
        clamp(16 + ((Y_R * red(argb) + Y_G * green(argb) + Y_B * blue(argb) + HALF) shr 16), 16, 235).toByte()

    private fun red(argb: Int) = (argb shr 16) and 0xFF
    private fun green(argb: Int) = (argb shr 8) and 0xFF
    private fun blue(argb: Int) = argb and 0xFF
    private fun clamp(value: Int, low: Int, high: Int) = if (value < low) low else if (value > high) high else value

    // BT.709 coefficients scaled to limited range (219 luma, 224 chroma steps) and to 1 << 16.
    private const val Y_R = 11966
    private const val Y_G = 40254
    private const val Y_B = 4064
    private const val CB_R = -6598
    private const val CB_G = -22186
    private const val CB_B = 28784
    private const val CR_R = 28784
    private const val CR_G = -26148
    private const val CR_B = -2636
    private const val HALF = 1 shl 15
}
