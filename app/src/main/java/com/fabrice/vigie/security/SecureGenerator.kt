package com.fabrice.vigie.security

import kotlin.random.Random

/**
 * Génération d'identifiants aléatoires lisibles : alphabet volontairement
 * réduit pour éviter les caractères qui prêtent à confusion à l'œil nu
 * (O/0, I/l/1, etc.) — adapté pour recopier à la main.
 */
object SecureGenerator {

    // Lettres sans I, L, O (majuscules et minuscules confondues)
    private const val LETTERS = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ"
    // Chiffres sans 0 ni 1
    private const val DIGITS = "23456789"
    // Symboles peu ambigus (pas de |, /, \ , `, ~, ^, {, }, <, >)
    private const val SYMBOLS = "!@#%&*?=+-_"

    private val ALL = LETTERS + DIGITS + SYMBOLS

    /**
     * Génère un mot de passe de [length] caractères contenant au moins une
     * lettre, un chiffre et un symbole (satisfait [PinHasher.isStrong] dès
     * 12 caractères).
     */
    fun password(length: Int = 16): String {
        val sb = StringBuilder()
        sb.append(LETTERS[Random.nextInt(LETTERS.length)])
        sb.append(DIGITS[Random.nextInt(DIGITS.length)])
        sb.append(SYMBOLS[Random.nextInt(SYMBOLS.length)])
        repeat(length - 3) {
            sb.append(ALL[Random.nextInt(ALL.length)])
        }
        val chars = sb.toString().toCharArray()
        // mélange Fisher-Yates
        for (i in chars.size - 1 downTo 1) {
            val j = Random.nextInt(i + 1)
            val tmp = chars[i]
            chars[i] = chars[j]
            chars[j] = tmp
        }
        return String(chars)
    }

    /** Génère un nom d'utilisateur (lettres + chiffres uniquement). */
    fun username(length: Int = 10): String {
        val chars = LETTERS + DIGITS
        return buildString {
            repeat(length) {
                append(chars[Random.nextInt(chars.length)])
            }
        }
    }

    /** Alphabet sans caractères ambigus — exposé pour tests. */
    internal val alphabet: String get() = ALL
}
