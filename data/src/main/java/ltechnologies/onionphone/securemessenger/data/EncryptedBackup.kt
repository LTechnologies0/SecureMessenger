package ltechnologies.onionphone.securemessenger.data

import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** AES-GCM backup envelope: magic + salt + iv + ciphertext. No plaintext leftover. */
object EncryptedBackup {
    private const val MAGIC = "SMBK1"
    private const val ITERATIONS = 210_000
    private const val KEY_LEN = 256
    private const val GCM_TAG_BITS = 128
    private const val SALT_LEN = 16
    private const val IV_LEN = 12

    fun encryptTo(plaintext: ByteArray, passphrase: CharArray, out: OutputStream) {
        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val key = derive(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext)
        out.write(MAGIC.toByteArray(Charsets.US_ASCII))
        out.write(salt)
        out.write(iv)
        out.write(ciphertext)
    }

    fun decryptFrom(input: InputStream, passphrase: CharArray): ByteArray {
        val magic = ByteArray(MAGIC.length)
        check(input.read(magic) == magic.size && magic.decodeToString() == MAGIC) { "Not a SecureMessenger backup" }
        val salt = ByteArray(SALT_LEN).also { check(input.read(it) == SALT_LEN) }
        val iv = ByteArray(IV_LEN).also { check(input.read(it) == IV_LEN) }
        val ciphertext = input.readBytes()
        val key = derive(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun derive(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, KEY_LEN)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val bytes = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }
}
