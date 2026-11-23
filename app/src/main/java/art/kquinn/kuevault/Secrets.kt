package art.kquinn.kuevault

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
 * Passwords for home computers, locked with a key that lives in the phone's own key store and never leaves
 * it. Only KueVault on this phone can unlock them; they are never in the report or any backup.
 */
object Secrets {
    private const val KEY = "kuevault-places"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    fun keep(context: Context, id: String, password: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val locked = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        val text = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(locked, Base64.NO_WRAP)
        prefs(context).edit().putString(id, text).apply()
    }

    fun read(context: Context, id: String): String? = runCatching {
        val text = prefs(context).getString(id, null) ?: return null
        val (iv, locked) = text.split(':', limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        String(cipher.doFinal(locked), Charsets.UTF_8)
    }.getOrNull()

    fun forget(context: Context, id: String) {
        prefs(context).edit().remove(id).apply()
    }
}
