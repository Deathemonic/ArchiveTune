package moe.rukamori.archivetune.sources.deezer

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class DeezerDataSource(
    private val upstream: DataSource,
    trackId: Long,
) : DataSource {
    private val cipher = Cipher.getInstance("Blowfish/CBC/NoPadding")
    private val key = SecretKeySpec(deriveKey(trackId), "Blowfish")
    private val iv = IvParameterSpec(ByteArray(8) { it.toByte() })
    private val block = ByteArray(BLOCK_SIZE)
    private var blockIndex = 0L
    private var blockPosition = 0
    private var blockLength = 0
    private var pendingSkip = 0
    private var remaining = C.LENGTH_UNSET.toLong()
    private var open = false

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        check(!open)
        val offset = (dataSpec.position % BLOCK_SIZE).toInt()
        val length = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            C.LENGTH_UNSET.toLong()
        } else {
            ((dataSpec.length + offset + BLOCK_SIZE - 1) / BLOCK_SIZE) * BLOCK_SIZE
        }
        blockIndex = dataSpec.position / BLOCK_SIZE
        blockPosition = 0
        blockLength = 0
        pendingSkip = offset
        open = true
        val available = upstream.open(
            dataSpec.buildUpon().setPosition(dataSpec.position - offset).setLength(length).build(),
        )
        val decodedLength = if (available == C.LENGTH_UNSET.toLong()) available else (available - offset).coerceAtLeast(0)
        remaining = when {
            dataSpec.length == C.LENGTH_UNSET.toLong() -> decodedLength
            decodedLength == C.LENGTH_UNSET.toLong() -> dataSpec.length
            else -> minOf(dataSpec.length, decodedLength)
        }

        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        check(open)

        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT

        if (blockPosition == blockLength) {
            blockPosition = 0
            blockLength = 0

            while (blockLength < BLOCK_SIZE) {
                val read = upstream.read(block, blockLength, BLOCK_SIZE - blockLength)

                if (read == C.RESULT_END_OF_INPUT) break
                if (read <= 0) throw IOException("Deezer stream stopped responding")

                blockLength += read
            }

            if (blockLength == 0) return C.RESULT_END_OF_INPUT

            if (blockLength == BLOCK_SIZE && blockIndex % 3 == 0L) {
                cipher.init(Cipher.DECRYPT_MODE, key, iv)
                val decoded = cipher.doFinal(block)
                decoded.copyInto(block)
            }

            blockIndex++
            blockPosition = pendingSkip.coerceAtMost(blockLength)
            pendingSkip = 0

            if (blockPosition == blockLength) return C.RESULT_END_OF_INPUT
        }

        val count = minOf(length.toLong(), (blockLength - blockPosition).toLong(), remaining.takeIf { it >= 0 } ?: Long.MAX_VALUE).toInt()
        block.copyInto(buffer, offset, blockPosition, blockPosition + count)
        blockPosition += count

        if (remaining >= 0) remaining -= count

        return count
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        open = false
        block.fill(0)
        upstream.close()
    }

    companion object {
        private const val BLOCK_SIZE = 2048

        internal fun deriveKey(trackId: Long): ByteArray {
            val hash = MessageDigest.getInstance("MD5").digest(trackId.toString().toByteArray(Charsets.US_ASCII))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val salt = "g4el58wc0zvf9na1"

            return ByteArray(16) { (hash[it].code xor hash[it + 16].code xor salt[it].code).toByte() }
        }
    }
}
