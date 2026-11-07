package id.altair.musevpn

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Menyimpan konfigurasi WireGuard (berisi PrivateKey) dalam bentuk AES-256-GCM
 * dengan kunci yang tidak pernah keluar dari Android Keystore (hardware-backed
 * kalau perangkat mendukung).
 */
object SecureStore {

    private const val ALIAS = "musevpn_conf_v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun save(target: File, plain: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val blob = cipher.iv + cipher.doFinal(plain)
        target.writeBytes(blob)
    }

    /** Mengembalikan null kalau file tidak ada / rusak / kunci sudah berubah. */
    fun load(source: File): ByteArray? {
        if (!source.exists()) return null
        return try {
            val blob = source.readBytes()
            if (blob.size <= IV_LEN) return null
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TAG_BITS, blob.copyOfRange(0, IV_LEN))
            )
            cipher.doFinal(blob.copyOfRange(IV_LEN, blob.size))
        } catch (_: Exception) {
            null
        }
    }

    fun delete(file: File) {
        runCatching { file.delete() }
    }
}
