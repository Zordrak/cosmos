package com.cosmos.screensaver.source

/** Reads image dimensions from the first bytes of a JPEG or PNG without decoding it. */
internal object ImageHeader {
    const val PREFIX_BYTES = 256 * 1024

    private val PNG_SIGNATURE = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    fun isJpeg(bytes: ByteArray) =
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()

    fun isPng(bytes: ByteArray) =
        bytes.size >= 8 && PNG_SIGNATURE.indices.all { (bytes[it].toInt() and 0xFF) == PNG_SIGNATURE[it] }

    fun dimensions(bytes: ByteArray): Pair<Int, Int>? = when {
        isPng(bytes) -> pngDimensions(bytes)
        isJpeg(bytes) -> jpegDimensions(bytes)
        else -> null
    }

    private fun pngDimensions(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 24) return null
        val width = int32(bytes, 16)
        val height = int32(bytes, 20)
        return if (width > 0 && height > 0) width to height else null
    }

    private fun jpegDimensions(bytes: ByteArray): Pair<Int, Int>? {
        var position = 2
        while (position + 4 <= bytes.size) {
            if (bytes[position] != 0xFF.toByte()) {
                position++
                continue
            }
            val marker = bytes[position + 1].toInt() and 0xFF
            if (marker == 0xFF) {
                position++
                continue
            }
            position += 2
            if (marker == 0x01 || marker in 0xD0..0xD9) continue
            if (position + 2 > bytes.size) return null
            val length = uint16(bytes, position)
            val isFrameHeader = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (isFrameHeader) {
                if (position + 7 > bytes.size) return null
                val height = uint16(bytes, position + 3)
                val width = uint16(bytes, position + 5)
                return if (width > 0 && height > 0) width to height else null
            }
            if (length < 2) return null
            position += length
        }
        return null
    }

    private fun uint16(bytes: ByteArray, at: Int) =
        ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

    private fun int32(bytes: ByteArray, at: Int) =
        (uint16(bytes, at) shl 16) or uint16(bytes, at + 2)
}
