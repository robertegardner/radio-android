package io.rg2.radio.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [TokenStore] encrypted with a non-exportable Android Keystore AES-GCM key.
 * Any decrypt/parse failure (key wiped on device restore, etc.) reads as
 * signed-out and clears the blob — the user just signs in again.
 */
class KeystoreTokenStore(context: Context) : TokenStore {
    private val prefs = context.getSharedPreferences("radio-auth", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    override fun load(): AuthState? = runCatching {
        val blob = Base64.decode(prefs.getString(KEY, null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12))
        json.decodeFromString(AuthState.serializer(), String(cipher.doFinal(blob, 12, blob.size - 12)))
    }.getOrElse { prefs.edit().remove(KEY).apply(); null }

    override fun save(state: AuthState?) {
        try {
            if (state == null) { prefs.edit().remove(KEY).apply(); return }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val ct = cipher.doFinal(json.encodeToString(AuthState.serializer(), state).toByteArray())
            prefs.edit().putString(KEY, Base64.encodeToString(cipher.iv + ct, Base64.NO_WRAP)).apply()
        } catch (e: Exception) {
            android.util.Log.w("KeystoreTokenStore", "token persist failed", e)
            prefs.edit().remove(KEY).apply()
        }
    }

    private companion object { const val ALIAS = "radio-auth"; const val KEY = "state" }
}
