package ltechnologies.onionphone.securemessenger.protocol.signal

import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.signal.core.models.backup.MessageBackupKey
import org.signal.core.util.readFully
import org.signal.core.util.readNBytesOrThrow
import org.signal.core.util.readVarInt32
import org.signal.core.util.stream.LimitedInputStream
import org.signal.core.util.stream.MacInputStream
import org.signal.core.util.writeVarInt32
import java.io.ByteArrayOutputStream

/**
 * Decrypts a Signal link-and-sync / local transfer archive (AES-CBC + GZIP + HMAC)
 * into a BackupInfo header and length-delimited Frame blobs — without depending on
 * Signal-Android Wire protos.
 *
 * Port of the crypto pipeline from Signal-Android EncryptedBackupReader
 * (`createForLocalOrLinking`).
 */
internal class SignalEncryptedBackupStream private constructor(
    val headerBytes: ByteArray,
    private val frameStream: InputStream,
) : AutoCloseable {

    fun frames(): Sequence<ByteArray> = sequence {
        while (true) {
            val frame = readFrame(frameStream) ?: break
            yield(frame)
        }
    }

    override fun close() {
        runCatching { frameStream.close() }
    }

    companion object {
        private const val MAC_SIZE = 32
        private const val MAX_FRAME_LENGTH = 25 * 1024 * 1024
        private const val MAX_FORWARD_SECRECY_METADATA_SIZE = 16 * 1024
        private val MAGIC_NUMBER = "SBACKUP".toByteArray(Charsets.UTF_8) + 0x01

        fun openForLinkAndSync(
            keyMaterial: MessageBackupKey.BackupKeyMaterial,
            length: Long,
            dataStream: () -> InputStream,
        ): SignalEncryptedBackupStream {
            val forwardSecrecyMetadata = dataStream().use { readForwardSecrecyMetadata(it) }
            val encryptedLength = if (forwardSecrecyMetadata != null) {
                val prefixLength = MAGIC_NUMBER.size +
                    forwardSecrecyMetadata.size.lengthAsVarInt32() +
                    forwardSecrecyMetadata.size
                length - prefixLength
            } else {
                length
            }

            val prefixSkippingStream = {
                if (forwardSecrecyMetadata == null) {
                    dataStream()
                } else {
                    dataStream().also { readForwardSecrecyMetadata(it) }
                }
            }

            prefixSkippingStream().use { validateMac(keyMaterial.macKey, encryptedLength, it) }

            val counting = CountingInputStream(prefixSkippingStream())
            val iv = counting.readNBytesOrThrow(16)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(keyMaterial.aesKey, "AES"),
                    IvParameterSpec(iv),
                )
            }
            val gzip = GZIPInputStream(
                CipherInputStream(
                    LimitedInputStream(counting, maxBytes = encryptedLength - MAC_SIZE),
                    cipher,
                ),
            )
            val header = readFrame(gzip)
                ?: throw IOException("Backup missing BackupInfo header")
            return SignalEncryptedBackupStream(header, gzip)
        }

        private fun readForwardSecrecyMetadata(stream: InputStream): ByteArray? {
            val potentialMagic = ByteArray(MAGIC_NUMBER.size)
            val n = stream.read(potentialMagic)
            if (n < MAGIC_NUMBER.size) return null
            if (!MAGIC_NUMBER.contentEquals(potentialMagic)) {
                // Not a forward-secrecy prefixed archive; caller must reopen.
                return null
            }
            val metadataLength = stream.readVarInt32()
            if (metadataLength < 0 || metadataLength > MAX_FORWARD_SECRECY_METADATA_SIZE) {
                throw IOException("Invalid forward secrecy metadata length: $metadataLength")
            }
            return stream.readNBytesOrThrow(metadataLength)
        }

        private fun validateMac(macKey: ByteArray, streamLength: Long, dataStream: InputStream) {
            val mac = Mac.getInstance("HmacSHA256").apply {
                init(SecretKeySpec(macKey, "HmacSHA256"))
            }
            val macStream = MacInputStream(
                wrapped = LimitedInputStream(dataStream, maxBytes = streamLength - MAC_SIZE),
                mac = mac,
            )
            macStream.readFully(false)
            val calculated = macStream.mac.doFinal()
            val expected = dataStream.readNBytesOrThrow(MAC_SIZE)
            if (!MessageDigest.isEqual(calculated, expected)) {
                throw IOException("Invalid backup MAC")
            }
        }

        private fun readFrame(stream: InputStream): ByteArray? {
            return try {
                val length = stream.readVarInt32().takeIf { it >= 0 } ?: return null
                if (length > MAX_FRAME_LENGTH) {
                    throw IOException("Frame length exceeds maximum: $length")
                }
                stream.readNBytesOrThrow(length)
            } catch (_: EOFException) {
                null
            }
        }

        private fun Int.lengthAsVarInt32(): Int =
            ByteArrayOutputStream().apply { writeVarInt32(this@lengthAsVarInt32) }.size()
    }

    /** Counts bytes read; used so MAC/IV positioning matches Signal-Android. */
    private class CountingInputStream(wrapped: InputStream) : FilterInputStream(wrapped) {
        var count: Long = 0
            private set

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) count++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) count += n
            return n
        }
    }
}
