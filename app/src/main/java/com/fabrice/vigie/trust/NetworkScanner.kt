package com.fabrice.vigie.trust

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Scan réseau local léger (patterns de network-scanner, allégé) :
 * ping sweep parallèle + lecture /proc/net/arp → appareils (IP, MAC, hostname).
 * Suffisant pour la présence de périphériques de confiance.
 */
object NetworkScanner {

    data class Device(
        val ip: String,
        val mac: String = "",
        val hostname: String = "",
        val alive: Boolean = true,
    )

    // ---------- Logique pure ----------

    fun ipToInt(ip: String): Long {
        val parts = ip.split(".")
        return parts.fold(0L) { acc, p -> (acc shl 8) or p.toLong() }
    }

    fun intToIp(value: Long): String =
        "${(value shr 24) and 0xFF}.${(value shr 16) and 0xFF}.${(value shr 8) and 0xFF}.${value and 0xFF}"

    fun networkAddress(ip: String, prefix: Int): Long {
        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        return ipToInt(ip) and mask
    }

    fun broadcastAddress(ip: String, prefix: Int): Long {
        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        return (ipToInt(ip) and mask) or ((1L shl (32 - prefix)) - 1)
    }

    fun hostList(ip: String, prefix: Int): List<Long> {
        val net = networkAddress(ip, prefix)
        val bcast = broadcastAddress(ip, prefix)
        return (net + 1 until bcast).toList()
    }

    private val IP_REGEX = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    /** Parse /proc/net/arp → map ip → mac. Ignore l'en-tête, les MAC nulles et « incomplete ». */
    fun parseArp(text: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (line in text.lines()) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 4) continue
            val ip = parts[0]
            val mac = parts[3]
            if (!IP_REGEX.matches(ip)) continue
            if (mac == "00:00:00:00:00:00" || mac == "incomplete" || mac.isEmpty()) continue
            result[ip] = mac.lowercase()
        }
        return result
    }

    fun normalizeMac(mac: String): String = mac.replace(":", "").replace("-", "").lowercase()

    /** Fabricant via un mini-OUI embarqué (quelques grands fabricants). */
    fun vendorFor(mac: String): String {
        val prefix = normalizeMac(mac).take(6)
        return OUI_DB[prefix] ?: ""
    }

    // ---------- Android ----------

    /** Détecte le sous-réseau Wi-Fi (IP + prefix). Retourne null si aucun réseau valide. */
    fun detectSubnet(): Pair<String, Int>? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (nif in interfaces) {
            if (!nif.isUp || nif.isLoopback) continue
            val name = nif.name
            if (name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("rmnet") ||
                name.startsWith("wg") || name.startsWith("tap") || name.startsWith("ipsec") ||
                name.startsWith("pptp") || name.startsWith("dummy")
            ) continue
            for (addr in nif.interfaceAddresses) {
                val inet4 = addr.address as? Inet4Address ?: continue
                val prefix = addr.networkPrefixLength?.toInt() ?: continue
                if (prefix in 16..30) {
                    return Pair(inet4.hostAddress, prefix)
                }
            }
        }
        return null
    }

    /** Ping un hôte (processus /system/bin/ping — fiable sur Android). */
    private fun pingHost(ip: String): Boolean {
        return try {
            val proc = ProcessBuilder("/system/bin/ping", "-c", "1", "-W", "1", ip)
                .redirectErrorStream(true)
                .start()
            val finished = proc.waitFor(3, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroy()
                false
            } else {
                proc.exitValue() == 0
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun readArp(): Map<String, String> {
        return try {
            parseArp(java.io.File("/proc/net/arp").readText())
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Scan complet du réseau local. Retourne la liste des appareils vus (ping ou ARP). */
    suspend fun scan(): List<Device> {
        val subnet = detectSubnet() ?: return emptyList()
        val (ip, prefix) = subnet
        val hosts = hostList(ip, prefix)
        if (hosts.isEmpty()) return emptyList()

        val live = ConcurrentHashMap.newKeySet<String>()
        val pool = Executors.newFixedThreadPool(64)
        for (h in hosts) {
            pool.execute {
                val hostIp = intToIp(h)
                if (pingHost(hostIp)) live.add(hostIp)
            }
        }
        pool.shutdown()
        pool.awaitTermination(120, TimeUnit.SECONDS)

        // Double lecture ARP pour attraper plus de MAC
        val arp1 = readArp()
        kotlinx.coroutines.delay(500)
        val arp2 = readArp()
        val arp = arp1 + arp2

        val allIps = (live + arp.keys).toSortedSet()
        return allIps.map { hostIp ->
            val mac = arp[hostIp] ?: ""
            val hostname = try {
                val h = InetAddress.getByName(hostIp).hostName
                if (h == hostIp) "" else h
            } catch (_: Exception) {
                ""
            }
            Device(ip = hostIp, mac = mac, hostname = hostname, alive = live.contains(hostIp))
        }
    }

    private val OUI_DB: Map<String, String> = mapOf(
        "0011e0" to "Apple",
        "0050f2" to "Microsoft",
        "0014a4" to "Samsung",
        "001122" to "Cimsys",
        "3cd0f8" to "Apple",
        "100d7f" to "Samsung",
        "8c8590" to "Samsung",
        "b090b6" to "Samsung",
        "f07816" to "Samsung",
        "a4c361" to "Samsung",
        "38b7dc" to "LG Electronics",
        "70b08c" to "LG Electronics",
        "f4cae5" to "LG Electronics",
        "586c8a" to "LG Electronics",
        "0026b0" to "Xiaomi",
        "2893fe" to "Xiaomi",
        "640980" to "Xiaomi",
        "8cd3a2" to "Xiaomi",
        "78f5fd" to "Xiaomi",
        "044965" to "Xiaomi",
        "f0f6c1" to "Xiaomi",
        "e468a3" to "Xiaomi",
        "88c3b3" to "TP-Link",
        "50c7bf" to "TP-Link",
        "a0f3c1" to "TP-Link",
        "d4ee07" to "TP-Link",
        "5c3b35" to "Freebox",
        "001e50" to "Freebox",
        "3431c4" to "Freebox",
        "f0def1" to "Freebox",
        "a863df" to "Sagemcom",
        "a078ba" to "Sagemcom",
        "2c3afd" to "Huawei",
        "4ca56d" to "Huawei",
        "78d752" to "Huawei",
        "9c3daf" to "Huawei",
        "28c6d2" to "Huawei",
        "000ec6" to "Raspberry Pi",
        "dca632" to "Raspberry Pi",
        "b827eb" to "Raspberry Pi",
        "48df37" to "Amazon",
        "74c246" to "Amazon",
        "78e3b5" to "Amazon",
        "a01d48" to "Amazon",
        "506a03" to "Sonos",
        "0004f2" to "Sonos",
        "7cfeb0" to "Nest",
        "1893bf" to "Google",
        "3c5ab4" to "Google",
        "0000f0" to "Sony",
        "a8d0e5" to "Sony",
        "0025ca" to "Sony",
        "a4e531" to "Sony",
        "f86601" to "Hewlett Packard",
        "3c5282" to "Hewlett Packard",
        "6c3bf9" to "Hewlett Packard",
        "9c8e99" to "Hewlett Packard",
        "00603d" to "Acer",
        "00e04c" to "Acer",
        "58d08e" to "Asus",
        "14dda9" to "Asus",
        "fc48ef" to "Asus",
        "8c5c6c" to "Asus",
        "3cd16e" to "Asus",
        "04bd70" to "Arris",
        "98fd74" to "Arris",
        "2421ab" to "Arris",
        "60d9c7" to "Arris",
        "dca4ca" to "Arris",
        "c8d15e" to "Arris",
        "000b5f" to "Netgear",
        "c0c1c0" to "Netgear",
        "a0741f" to "Netgear",
        "483fda" to "Netgear",
        "981f0a" to "Netgear",
        "94bf2d" to "Garmin",
        "008ee6" to "Garmin",
        "4c7c6a" to "Garmin",
        "bc327b" to "Garmin",
        "002dd3" to "Fitbit",
        "b46d35" to "Withings",
        "009e63" to "Withings",
        "00ffac" to "Withings",
        "80bd19" to "Bose",
        "98d6bb" to "Bose",
        "0cf0b4" to "Bose",
        "7c0d49" to "Samsung",
        "5087b8" to "Samsung",
    )
}
