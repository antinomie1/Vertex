package dev.vertex.runtime

import java.nio.ByteBuffer

/** Deterministic 64-bit hash over visible RGBA bytes; backend row padding is excluded. */
object FrameHash {
    fun rgba(bytes: ByteBuffer, width: Int, height: Int, rowStride: Int): Long {
        require(width > 0 && height > 0 && rowStride >= width * 4 && bytes.limit() >= rowStride * height)
        var hash = -0x340d631b7bdddcdbL
        for (y in 0 until height) for (x in 0 until width * 4) {
            hash = (hash xor (bytes.get(y * rowStride + x).toLong() and 0xff)) * 0x100000001b3L
        }
        return hash
    }
}
