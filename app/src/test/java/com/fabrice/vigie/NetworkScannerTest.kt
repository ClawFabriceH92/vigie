package com.fabrice.vigie

import com.fabrice.vigie.trust.NetworkScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkScannerTest {

    @Test
    fun `ipToInt et intToIp round trip`() {
        assertEquals(0xC0A80001L, NetworkScanner.ipToInt("192.168.0.1"))
        assertEquals("192.168.0.1", NetworkScanner.intToIp(NetworkScanner.ipToInt("192.168.0.1")))
        assertEquals("10.0.0.254", NetworkScanner.intToIp(NetworkScanner.ipToInt("10.0.0.254")))
    }

    @Test
    fun `networkAddress masque`() {
        assertEquals(0xC0A80000L, NetworkScanner.networkAddress("192.168.0.42", 24))
        assertEquals(0x0A000000L, NetworkScanner.networkAddress("10.20.30.40", 8))
    }

    @Test
    fun `broadcastAddress`() {
        assertEquals(0xC0A800FFL, NetworkScanner.broadcastAddress("192.168.0.42", 24))
    }

    @Test
    fun `hostList 254 hotes pour un 24`() {
        val hosts = NetworkScanner.hostList("192.168.0.1", 24)
        assertEquals(254, hosts.size)
        assertTrue(hosts.contains(NetworkScanner.ipToInt("192.168.0.254")))
        assertTrue(!hosts.contains(NetworkScanner.ipToInt("192.168.0.255")))
        assertTrue(!hosts.contains(NetworkScanner.ipToInt("192.168.0.0")))
    }

    @Test
    fun `parseArp ignore en-tete mac nulles et incomplete`() {
        val arp = """
            IP address       HW type     Flags       HW address            Mask     Device
            192.168.0.1      0x1         0x2         aa:bb:cc:dd:ee:ff     *        eth0
            192.168.0.42     0x1         0x2         00:00:00:00:00:00     *        eth0
            192.168.0.43     0x1         0x2         incomplete            *        eth0
            not-an-ip        0x1         0x2         11:22:33:44:55:66     *        eth0
            192.168.0.44     0x1         0x2         12:34:56:78:9a:bc     *        eth0
        """.trimIndent()
        val parsed = NetworkScanner.parseArp(arp)
        assertEquals(2, parsed.size)
        assertEquals("aa:bb:cc:dd:ee:ff", parsed["192.168.0.1"])
        assertEquals("12:34:56:78:9a:bc", parsed["192.168.0.44"])
    }

    @Test
    fun `parseArp normalise les mac en minuscules`() {
        val parsed = NetworkScanner.parseArp("192.168.0.1 0x1 0x2 AA:BB:CC:DD:EE:FF * eth0")
        assertEquals("aa:bb:cc:dd:ee:ff", parsed["192.168.0.1"])
    }

    @Test
    fun `normalizeMac`() {
        assertEquals("aabbccddeeff", NetworkScanner.normalizeMac("AA:BB:CC:DD:EE:FF"))
        assertEquals("aabbccddeeff", NetworkScanner.normalizeMac("aa-bb-cc-dd-ee-ff"))
        assertEquals("aabbccddeeff", NetworkScanner.normalizeMac("aabbccddeeff"))
    }

    @Test
    fun `vendorFor connu et inconnu`() {
        assertEquals("Samsung", NetworkScanner.vendorFor("a4:c3:61:00:00:00"))
        assertEquals("Raspberry Pi", NetworkScanner.vendorFor("b8:27:eb:12:34:56"))
        assertEquals("", NetworkScanner.vendorFor("00:00:00:00:00:01"))
    }
}
