package com.fabrice.vigie

import com.fabrice.vigie.security.PinHasher
import com.fabrice.vigie.security.SecureGenerator
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureGeneratorTest {

    @Test
    fun `mot de passe respecte la politique forte`() {
        repeat(50) {
            val pwd = SecureGenerator.password()
            assertTrue("longueur", pwd.length == 16)
            assertTrue("fort : $pwd", PinHasher.isStrong(pwd))
        }
    }

    @Test
    fun `mot de passe sans caractere ambigu`() {
        repeat(50) {
            val pwd = SecureGenerator.password()
            for (c in pwd) {
                assertTrue("caractère ambigu : $c", c !in "O0Il1|/\\`~^{}<>")
            }
        }
    }

    @Test
    fun `username sans caractere ambigu ni symbole`() {
        repeat(50) {
            val user = SecureGenerator.username()
            assertTrue("longueur", user.length == 10)
            for (c in user) {
                assertTrue("caractère interdit : $c", c.isLetterOrDigit() && c !in "O0Il1")
            }
        }
    }
}
