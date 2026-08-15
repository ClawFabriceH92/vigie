package com.fabrice.vigie

import com.fabrice.vigie.security.PinHasher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinHasherTest {

    @Test
    fun `hash est deterministe`() {
        assertEquals(PinHasher.hash("1234"), PinHasher.hash("1234"))
    }

    @Test
    fun `hash differents pins differents`() {
        assertNotEquals(PinHasher.hash("1234"), PinHasher.hash("1235"))
    }

    @Test
    fun `verify correct et incorrect`() {
        val h = PinHasher.hash("4821")
        assertTrue(PinHasher.verify("4821", h))
        assertFalse(PinHasher.verify("4822", h))
    }

    @Test
    fun `hash fait 64 caracteres hex`() {
        assertEquals(64, PinHasher.hash("0000").length)
    }
}
