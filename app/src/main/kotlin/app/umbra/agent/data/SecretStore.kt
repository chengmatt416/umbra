package app.umbra.agent.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API keys and other secrets, encrypted with an AES-GCM key that lives in the Android Keystore and
 * never leaves it. The ciphertext sits in private preferences. A secret never reaches a log, the
 * session timeline or a command line; the only place its value goes is the environment of the
 * process that needs it (spec §28).
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    fun names(): Set<String> = prefs.all.keys.toSet()

    fun has(name: String): Boolean = prefs.contains(name)

    fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return try {
            decrypt(stored)
        } catch (_: Exception) {
            // The key was lost (a restored backup, a changed lock screen policy): the secret is unreadable, so ask again.
            null
        }
    }

    fun put(name: String, value: String) {
        require(NAME.matches(name)) { "invalid secret name '$name'" }
        if (value.isEmpty()) remove(name) else prefs.edit().putString(name, encrypt(value)).apply()
    }

    fun remove(name: String) {
        prefs.edit().remove(name).apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(body, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val (iv, body) = stored.split(':', limit = 2)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(body, Base64.NO_WRAP)), Charsets.UTF_8)
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "umbra.secrets.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val NAME = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")
    }
}
