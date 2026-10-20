package moe.rukamori.archivetune.playback

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object UsbPcm {
    fun bytesPerSample(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
        else -> error("Unsupported PCM encoding")
    }

    fun convert(input: ByteBuffer, encoding: Int, bytes: Int, output: ByteBuffer = ByteBuffer.allocateDirect(bytes / bytesPerSample(encoding) * 4)): ByteBuffer {
        require(bytes % bytesPerSample(encoding) == 0)
        val position = input.position()
        val limit = input.limit()
        val order = input.order()
        require(bytes in 0..input.remaining())
        require(output.capacity() >= bytes / bytesPerSample(encoding) * 4)
        val source = input.order(ByteOrder.LITTLE_ENDIAN)
        source.limit(position + bytes)
        output.clear()
        output.order(ByteOrder.LITTLE_ENDIAN)

        try {
            while (source.hasRemaining()) {
                output.putInt(
                    when (encoding) {
                        C.ENCODING_PCM_16BIT -> source.short.toInt() shl 16

                        C.ENCODING_PCM_24BIT -> {
                            val a = source.get().toInt() and 255
                            val b = source.get().toInt() and 255
                            val c = source.get().toInt()
                            (a shl 8) or (b shl 16) or (c shl 24)
                        }

                        C.ENCODING_PCM_32BIT -> source.int

                        C.ENCODING_PCM_FLOAT -> {
                            val value = source.float
                            require(value.isFinite() && value >= -1f && value <= 1f) { "Invalid PCM sample" }
                            (value.toDouble() * 2147483648.0).toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
                        }

                        else -> error("Unsupported PCM encoding")
                    },
                )
            }

            return output.flip() as ByteBuffer
        } finally {
            input.limit(limit)
            input.position(position)
            input.order(order)
        }
    }
}
