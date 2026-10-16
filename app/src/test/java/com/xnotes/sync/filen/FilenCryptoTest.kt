package com.xnotes.sync.filen

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Validates the v2 auth derivation and name hashing against vectors computed independently
 * (Python hashlib), plus a chunk-crypto round trip. These paths use only the JVM crypto
 * providers, so they run without an Android device. (Argon2id and metadata base64 need
 * Android and are exercised on-device.)
 */
class FilenCryptoTest {
    @Test
    fun deriveAuthV2MatchesReferenceVector() {
        val derived = FilenCrypto.deriveAuth("password123", "abcdef0123456789", 2)
        assertEquals("fb34609067d8098326ea35434abf1317759e63d5452a3f3adddd0bbd09c70789", derived.masterKey)
        assertEquals(
            "dfd114da124ca18a4d3fe660bd8c2dc979c533cbce0ed52ead7920ee0e1336679ad031e8b7ef640c530585573a39cf5332dbc84fbcf5775f7029f2610b7e5ec9",
            derived.password,
        )
    }

    @Test
    fun hashFileNameLowercasesAndMatchesReference() {
        assertEquals("540d06626d78f30a854eaa7b31fb01bf31789c8b", FilenCrypto.hashFileNameV2("Note.xnote"))
    }

    @Test
    fun chunkEncryptionRoundTrips() {
        val key = FilenCrypto.generateFileKey()
        val plaintext = "the quick brown fox".repeat(5000).toByteArray()
        val decrypted = FilenCrypto.decryptChunk(FilenCrypto.encryptChunk(plaintext, key), key)
        assertArrayEquals(plaintext, decrypted)
    }
}
