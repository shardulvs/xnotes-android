package com.xnotes.sync.filen

import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import com.lambdapioneer.argon2kt.Argon2Version
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Filen end-to-end crypto, ported from the official filen-sdk-ts so notes stay
 * readable by the web/desktop clients. Only the v2 metadata/file scheme is
 * implemented (prefix "002", AES-256-GCM); auth supports both v2 (PBKDF2) and v3
 * (Argon2id) login. Getting any constant wrong corrupts data, so every value here
 * mirrors the SDK exactly.
 */
object FilenCrypto {
    private const val RANDOM_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private val secureRandom = SecureRandom()

    data class DerivedAuth(val masterKey: String, val password: String)

    /** auth/info -> derive the login password and master key. Matches generatePasswordAndMasterKeyBasedOnAuthVersion. */
    fun deriveAuth(rawPassword: String, salt: String, authVersion: Int): DerivedAuth = when (authVersion) {
        2 -> {
            // PBKDF2-SHA512, salt as raw UTF-8 bytes of the hex string, 512-bit output split in half.
            val derived = pbkdf2Sha512(rawPassword.toByteArray(Charsets.UTF_8), salt.toByteArray(Charsets.UTF_8), 200_000, 64)
            val hex = derived.toHex()
            val masterKey = hex.substring(0, hex.length / 2)
            val passwordHalf = hex.substring(hex.length / 2)
            // The password half is hashed once more with SHA-512 before it is sent.
            DerivedAuth(masterKey, sha512(passwordHalf.toByteArray(Charsets.UTF_8)).toHex())
        }
        3 -> {
            // Argon2id, salt hex-decoded, 64-byte output split in half, no extra hashing.
            val out = argon2idRaw(rawPassword.toByteArray(Charsets.UTF_8), salt.hexToBytes())
            val hex = out.toHex()
            DerivedAuth(hex.substring(0, hex.length / 2), hex.substring(hex.length / 2))
        }
        else -> throw IllegalArgumentException("Unsupported Filen auth version: $authVersion")
    }

    private fun argon2idRaw(password: ByteArray, salt: ByteArray): ByteArray =
        Argon2Kt().hash(
            mode = Argon2Mode.ARGON2_ID,
            password = password,
            salt = salt,
            tCostInIterations = 3,
            mCostInKibibyte = 65_536,
            parallelism = 4,
            hashLengthInBytes = 64,
            version = Argon2Version.V13,
        ).rawHashAsByteArray()

    /** Metadata encryption v2. Output: "002" + 12-char ASCII IV + base64(ciphertext||tag). */
    fun encryptMetadata(plaintext: String, masterKey: String): String {
        val key = deriveMetadataKey(masterKey)
        val iv = randomString(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv.toByteArray(Charsets.UTF_8)))
        val out = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return "002" + iv + android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP)
    }

    /** Metadata decryption. Tries the given master keys until one succeeds. Returns null if none work. */
    fun decryptMetadata(metadata: String, masterKeys: List<String>): String? {
        if (metadata.length < 15 || !metadata.startsWith("002")) return null
        val iv = metadata.substring(3, 15).toByteArray(Charsets.UTF_8)
        val body = try {
            android.util.Base64.decode(metadata.substring(15), android.util.Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            return null
        }
        for (mk in masterKeys) {
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(deriveMetadataKey(mk), "AES"), GCMParameterSpec(128, iv))
                return String(cipher.doFinal(body), Charsets.UTF_8)
            } catch (e: Exception) {
                // Wrong key or tag mismatch: try the next master key.
            }
        }
        return null
    }

    /** File chunk encryption v2. Output: 12-byte ASCII IV || ciphertext || tag. */
    fun encryptChunk(data: ByteArray, fileKey: String): ByteArray {
        val iv = randomString(12).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(fileKey.toByteArray(Charsets.UTF_8), "AES"), GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(data)
    }

    /** File chunk decryption v2. Input layout is the encryptChunk output. */
    fun decryptChunk(data: ByteArray, fileKey: String): ByteArray {
        val iv = data.copyOfRange(0, 12)
        val body = data.copyOfRange(12, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(fileKey.toByteArray(Charsets.UTF_8), "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(body)
    }

    /** nameHashed for dir/create and upload/done (auth v2): sha1(hex(sha512(lowercased name))). */
    fun hashFileNameV2(name: String): String {
        val sha512Hex = sha512(name.lowercase().toByteArray(Charsets.UTF_8)).toHex()
        return sha1(sha512Hex.toByteArray(Charsets.UTF_8)).toHex()
    }

    /** Per-file encryption key: 32-char random string used directly as AES-256 key bytes. */
    fun generateFileKey(): String = randomString(32)

    fun randomString(length: Int): String {
        val sb = StringBuilder(length)
        repeat(length) { sb.append(RANDOM_CHARS[secureRandom.nextInt(RANDOM_CHARS.length)]) }
        return sb.toString()
    }

    fun uuidV4(): String = java.util.UUID.randomUUID().toString()

    /** SHA-512 hex of the full plaintext, stored in file metadata (matches the SDK's fileHasher). */
    fun sha512Hex(data: ByteArray): String = sha512(data).toHex()

    // Metadata key: PBKDF2(masterKey, salt=masterKey, 1 iteration, SHA-512, 256-bit) -> 32 raw bytes.
    private fun deriveMetadataKey(masterKey: String): ByteArray {
        val bytes = masterKey.toByteArray(Charsets.UTF_8)
        return pbkdf2Sha512(bytes, bytes, 1, 32)
    }

    private fun pbkdf2Sha512(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(password, "HmacSHA512"))
        val hLen = mac.macLength
        val blocks = (dkLen + hLen - 1) / hLen
        val out = ByteArray(blocks * hLen)
        val block = ByteArray(4)
        for (i in 1..blocks) {
            block[0] = (i ushr 24).toByte(); block[1] = (i ushr 16).toByte(); block[2] = (i ushr 8).toByte(); block[3] = i.toByte()
            mac.update(salt)
            var u = mac.doFinal(block)
            val t = u.copyOf()
            for (iter in 2..iterations) {
                u = mac.doFinal(u)
                for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            System.arraycopy(t, 0, out, (i - 1) * hLen, hLen)
        }
        return out.copyOf(dkLen)
    }

    private fun sha512(data: ByteArray) = MessageDigest.getInstance("SHA-512").digest(data)
    private fun sha1(data: ByteArray) = MessageDigest.getInstance("SHA-1").digest(data)

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) { val v = b.toInt() and 0xff; sb.append("0123456789abcdef"[v ushr 4]); sb.append("0123456789abcdef"[v and 0x0f]) }
        return sb.toString()
    }

    private fun String.hexToBytes(): ByteArray {
        val out = ByteArray(length / 2)
        for (i in out.indices) out[i] = ((Character.digit(this[i * 2], 16) shl 4) + Character.digit(this[i * 2 + 1], 16)).toByte()
        return out
    }
}
