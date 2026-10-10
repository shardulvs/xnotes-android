package com.xnotes.sync.filen

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the Filen session (apiKey + master keys) encrypted with an AndroidKeyStore
 * AES key, so credentials never sit in plaintext settings. The blob lives in a private
 * SharedPreferences; the key never leaves the keystore.
 */
class FilenSecureStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("filen_secure", Context.MODE_PRIVATE)

    fun save(session: FilenSession) {
        val json = JSONObject()
            .put("email", session.email)
            .put("apiKey", session.apiKey)
            .put("masterKeys", JSONArray(session.masterKeys))
            .put("authVersion", session.authVersion)
            .put("baseFolderUuid", session.baseFolderUuid)
            .toString()
        prefs.edit().putString("session", encrypt(json)).apply()
    }

    fun load(): FilenSession? {
        val blob = prefs.getString("session", null) ?: return null
        val json = try {
            JSONObject(decrypt(blob))
        } catch (e: Exception) {
            return null
        }
        val keysArr = json.optJSONArray("masterKeys") ?: return null
        val keys = (0 until keysArr.length()).map { keysArr.getString(it) }
        if (keys.isEmpty()) return null
        return FilenSession(
            json.optString("email"), json.optString("apiKey"), keys,
            json.optInt("authVersion", 2), json.optString("baseFolderUuid"),
        )
    }

    fun clear() = prefs.edit().remove("session").apply()

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + ct, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val iv = raw.copyOfRange(0, 12)
        val ct = raw.copyOfRange(12, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "n_notes_filen_key"
    }
}
