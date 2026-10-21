package ltechnologies.onionphone.securemessenger.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EncryptedBackupTest {
    @Test
    fun roundTripEncryptDecrypt() {
        val plain = "secure-messenger-backup".toByteArray()
        val pass = "passphrase-ok".toCharArray()
        val out = ByteArrayOutputStream()
        EncryptedBackup.encryptTo(plain, pass, out)
        val recovered = EncryptedBackup.decryptFrom(ByteArrayInputStream(out.toByteArray()), pass)
        assertArrayEquals(plain, recovered)
    }

    @Test
    fun wrongPassphraseFails() {
        val out = ByteArrayOutputStream()
        EncryptedBackup.encryptTo("x".toByteArray(), "correct-pass".toCharArray(), out)
        assertThrows(Exception::class.java) {
            EncryptedBackup.decryptFrom(ByteArrayInputStream(out.toByteArray()), "wrong-pass".toCharArray())
        }
    }
}
