package com.fabrice.vigie.security

import java.security.MessageDigest

/**
 * Mot de passe : hash SHA-256 stocké, vérification à temps constant-ish.
 * Logique pure (testable) ; le stockage SharedPreferences est isolé dans [PinStore].
 */
object PinHasher {

    const val MIN_LENGTH = 12

    fun hash(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(pin.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun verify(pin: String, storedHash: String): Boolean = hash(pin) == storedHash

    /**
     * Mot de passe fort : au moins 12 caractères, avec au moins une lettre,
     * un chiffre et un symbole (tout caractère ni lettre ni chiffre).
     */
    fun isStrong(password: String): Boolean {
        if (password.length < MIN_LENGTH) return false
        var hasLetter = false
        var hasDigit = false
        var hasSymbol = false
        for (c in password) {
            when {
                c.isLetter() -> hasLetter = true
                c.isDigit() -> hasDigit = true
                else -> hasSymbol = true
            }
        }
        return hasLetter && hasDigit && hasSymbol
    }
}
