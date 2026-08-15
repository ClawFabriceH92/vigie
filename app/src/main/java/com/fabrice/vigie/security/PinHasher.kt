package com.fabrice.vigie.security

import java.security.MessageDigest

/**
 * PIN : hash SHA-256 stocké, vérification à temps constant-ish.
 * Logique pure (testable) ; le stockage SharedPreferences est isolé dans [PinStore].
 */
object PinHasher {

    fun hash(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(pin.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun verify(pin: String, storedHash: String): Boolean = hash(pin) == storedHash
}
