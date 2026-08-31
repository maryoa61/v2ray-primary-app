package com.example

import com.example.data.ServerEntity
import com.example.service.HevSocks5Tunnel
import com.example.service.XrayConfigGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure-JVM tests for the Xray/hev config that guards the tunnel stability
 * fixes (sniffing, split DNS, dns-out, MTU propagation).
 *
 * These intentionally avoid org.json / Android so they run on the host JVM
 * with the plain unit-test runner.
 */
class XrayConfigGeneratorTest {

    private val sampleServer = ServerEntity(
        name = "Test VLESS",
        type = "VLESS",
        address = "node.example.com",
        port = 443,
        uuid = "11111111-2222-3333-4444-555555555555",
        network = "ws",
        path = "/ws",
        host = "node.example.com",
        tls = true,
        security = "tls",
        sni = "node.example.com"
    )

    private fun minimalJsonCheck(json: String) {
        // Brace/bracket balance — catches a broken template interpolation.
        fun balanced(input: String, open: Char, close: Char): Boolean {
            var depth = 0
            var inString = false
            var escaped = false
            for (ch in input) {
                if (escaped) { escaped = false; continue }
                when {
                    ch == '\\' && inString -> escaped = true
                    ch == '"' -> inString = !inString
                    inString -> {}
                    ch == open -> depth++
                    ch == close -> {
                        depth--
                        if (depth < 0) return false
                    }
                }
            }
            return depth == 0 && !inString
        }
        assertTrue("unbalanced braces", balanced(json, '{', '}'))
        assertTrue("unbalanced brackets", balanced(json, '[', ']'))
    }

    @Test
    fun `config is structurally balanced`() {
        val json = XrayConfigGenerator.generate(sampleServer, filesDir = null)
        minimalJsonCheck(json)
    }

    @Test
    fun `sniffing is enabled on both inbounds`() {
        val json = XrayConfigGenerator.generate(sampleServer, filesDir = null)
        // Regression guard: sniffing must be ENABLED — otherwise domain-based
        // routing (.ir direct) never matches because hev sends IP literals.
        assertTrue("sniffing block must enable sniffing", json.contains("\"enabled\": true"))
        assertTrue("quic must be in destOverride for HTTP/3", json.contains("\"quic\""))
        assertFalse("sniffing must not be disabled", json.contains("\"enabled\": false"))
    }

    @Test
    fun `split DNS and dns-out outbound are present`() {
        val json = XrayConfigGenerator.generate(sampleServer, filesDir = null)
        assertTrue("dns block present", json.contains("\"dns\""))
        // Shecan domestic resolvers for .ir domains
        assertTrue("shecan primary DNS", json.contains("178.22.122.100"))
        assertTrue("shecan secondary DNS", json.contains("185.51.200.2"))
        // dns-out handles intercepted port-53 traffic
        assertTrue("dns-out outbound present", json.contains("\"dns-out\""))
        assertTrue(
            "port 53 routed to dns-out",
            json.contains("\"outboundTag\": \"dns-out\"")
        )
    }

    @Test
    fun `routing strategy is AsIs for low latency`() {
        val json = XrayConfigGenerator.generate(sampleServer, filesDir = null)
        // IPIfNonMatch forced an extra edge-side DNS resolution per connection.
        assertTrue(json.contains("\"domainStrategy\": \"AsIs\""))
        assertFalse(json.contains("IPIfNonMatch"))
    }

    @Test
    fun `domestic DNS IPs are in the direct rule even without geoip`() {
        // filesDir == null -> hasGeoip == false -> geoip entries omitted.
        val json = XrayConfigGenerator.generate(sampleServer, filesDir = null)
        assertFalse("geoip must be skipped without geoip.dat", json.contains("geoip"))
        // But Shecan resolvers must still be forced direct by explicit IP rule.
        assertTrue(json.contains("178.22.122.100"))
    }

    @Test
    fun `geoip entries are included when geoip dat exists`() {
        val tmp = createTempDir()
        try {
            File(tmp, "geoip.dat").writeText("stub")
            val json = XrayConfigGenerator.generate(sampleServer, filesDir = tmp)
            assertTrue(json.contains("geoip:private"))
            assertTrue(json.contains("geoip:ir"))
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `hev config propagates MTU and uses safe task stack`() {
        val tmp = File.createTempFile("hev", ".yml")
        try {
            HevSocks5Tunnel.writeConfig(tmp, socksPort = 10808, mtu = 1420)
            val yml = tmp.readText()
            assertTrue("mtu must match TUN MTU", yml.contains("mtu: 1420"))
            // 20480 was too small and caused native stack overflow aborts.
            assertTrue(
                "task stack must be the documented default (86016)",
                yml.contains("task-stack-size: 86016")
            )
            assertTrue("single queue on Android", yml.contains("multi-queue: false"))
            // connect-timeout is the correct upstream key (connect-ipv4/6 do
            // not exist and were previously mistakenly considered).
            assertTrue("connect-timeout present", yml.contains("connect-timeout: 5000"))
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `hev config clamps out-of-range MTU`() {
        val tmp = File.createTempFile("hev", ".yml")
        try {
            HevSocks5Tunnel.writeConfig(tmp, socksPort = 10808, mtu = 9000)
            val yml = tmp.readText()
            // Must clamp into [1280,1500], never emit the jumbo value.
            assertFalse(yml.contains("mtu: 9000"))
            assertTrue(yml.contains("mtu: 1500"))
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `special characters in user inputs are JSON escaped`() {
        val evil = sampleServer.copy(
            sni = "evil\".example.com",
            path = "/p\\ath"
        )
        val json = XrayConfigGenerator.generate(evil, filesDir = null)
        minimalJsonCheck(json)
        assertTrue(json.contains("evil\\\".example.com"))
    }

    @Test
    fun `all protocol types generate balanced configs`() {
        listOf("VLESS", "VMESS", "TROJAN", "SHADOWSOCKS").forEach { type ->
            val json = XrayConfigGenerator.generate(sampleServer.copy(type = type), filesDir = null)
            minimalJsonCheck(json)
        }
    }

    @Test
    fun `default tunnel MTU is within safe bounds`() {
        assertTrue(HevSocks5Tunnel.TUNNEL_MTU in 1280..1500)
        assertEquals(1400, HevSocks5Tunnel.TUNNEL_MTU)
    }
}
