package com.example

import com.example.data.ServerEntity
import com.example.service.HevSocks5Tunnel
import com.example.service.RuntimeSettings
import com.example.service.XrayConfigGenerator
import com.example.service.effectiveDnsServers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure-JVM tests for the Xray/hev config that guards the tunnel stability
 * fixes (sniffing, DNS transport routing, encrypted bootstrap DoH, MTU propagation).
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

    private fun outboundObject(json: String, tag: String): String {
        val outboundsKey = json.indexOf("\"outbounds\"")
        assertTrue("outbounds array is present", outboundsKey >= 0)
        val arrayStart = json.indexOf('[', outboundsKey)
        assertTrue("outbounds array starts", arrayStart >= 0)

        var objectStart = -1
        var objectDepth = 0
        var inString = false
        var escaped = false
        for (index in arrayStart + 1 until json.length) {
            val char = json[index]
            if (inString) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') inString = false
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> {
                    if (objectDepth == 0) objectStart = index
                    objectDepth++
                }
                '}' -> {
                    objectDepth--
                    if (objectDepth == 0 && objectStart >= 0) {
                        val candidate = json.substring(objectStart, index + 1)
                        if (candidate.contains("\"tag\": \"$tag\"")) return candidate
                        objectStart = -1
                    }
                }
                ']' -> if (objectDepth == 0) break
            }
        }
        return ""
    }

    @Test
    fun `config is structurally balanced`() {
        val json = XrayConfigGenerator.generate(sampleServer, filesDir = null)
        minimalJsonCheck(json)
    }

    @Test
    fun `config supports isolated temporary inbound ports for real ping probes`() {
        val json = XrayConfigGenerator.generate(
            sampleServer,
            filesDir = null,
            socksPort = 45123,
            httpPort = 45124
        )
        assertTrue(json.contains("\"port\": 45123"))
        assertTrue(json.contains("\"port\": 45124"))
    }

    @Test
    fun `fragment is opt-in and emits a compatible freedom dialer chain`() {
        val defaultJson = XrayConfigGenerator.generate(sampleServer, filesDir = null)
        assertFalse(defaultJson.contains("\"tag\": \"fragment\""))
        assertFalse(defaultJson.contains("\"dialerProxy\": \"fragment\""))

        val json = XrayConfigGenerator.generate(
            sampleServer,
            filesDir = null,
            runtimeSettings = RuntimeSettings(
                fragmentEnabled = true,
                fragmentPackets = "tlshello",
                fragmentLength = "60-150",
                fragmentInterval = "7-15",
                fragmentMaxSplit = "10"
            )
        )
        minimalJsonCheck(json)
        val proxyOutbound = outboundObject(json, "proxy")
        val fragmentOutbound = outboundObject(json, "fragment")
        assertTrue("main proxy outbound chains through fragment", proxyOutbound.contains("\"dialerProxy\": \"fragment\""))
        assertTrue("fragment is a Freedom outbound", fragmentOutbound.contains("\"protocol\": \"freedom\""))
        assertTrue(fragmentOutbound.contains("\"packets\": \"tlshello\""))
        assertTrue(fragmentOutbound.contains("\"length\": \"60-150\""))
        assertTrue(fragmentOutbound.contains("\"interval\": \"7-15\""))
        assertTrue(fragmentOutbound.contains("\"maxSplit\": \"10\""))
    }

    @Test
    fun `temporary ping config keeps the persisted fragment chain`() {
        val json = XrayConfigGenerator.generate(
            sampleServer,
            filesDir = null,
            socksPort = 45123,
            httpPort = 45124,
            runtimeSettings = RuntimeSettings(
                fragmentEnabled = true,
                fragmentPackets = "2-4",
                fragmentLength = "60-120",
                fragmentInterval = "5-12",
                fragmentMaxSplit = "8"
            )
        )
        minimalJsonCheck(json)
        assertTrue(json.contains("\"port\": 45123"))
        assertTrue(json.contains("\"port\": 45124"))
        assertTrue(outboundObject(json, "proxy").contains("\"dialerProxy\": \"fragment\""))
        assertTrue(outboundObject(json, "fragment").contains("\"packets\": \"2-4\""))
    }

    @Test
    fun `tlshello fragment maps to first packets for reality`() {
        val realityServer = sampleServer.copy(
            tls = false,
            security = "reality",
            network = "tcp",
            publicKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            shortId = "abcd"
        )
        val json = XrayConfigGenerator.generate(
            realityServer,
            filesDir = null,
            runtimeSettings = RuntimeSettings(fragmentEnabled = true)
        )
        minimalJsonCheck(json)
        assertTrue(json.contains("\"packets\": \"1-3\""))
        assertFalse(json.contains("\"packets\": \"tlshello\""))
    }

    @Test
    fun `invalid fragment values fall back to safe defaults`() {
        val json = XrayConfigGenerator.generate(
            sampleServer,
            filesDir = null,
            runtimeSettings = RuntimeSettings(
                fragmentEnabled = true,
                fragmentPackets = "not-a-mode",
                fragmentLength = "50-100\"}",
                fragmentInterval = "-2",
                fragmentMaxSplit = "0"
            )
        )
        minimalJsonCheck(json)
        assertTrue(json.contains("\"packets\": \"1-3\""))
        assertTrue(json.contains("\"length\": \"50-100\""))
        assertTrue(json.contains("\"interval\": \"10-20\""))
        assertTrue(json.contains("\"maxSplit\": \"10\""))
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
    fun `app DNS ports stay on proxy and proxy hostname bootstrap uses encrypted DoH`() {
        val json = XrayConfigGenerator.generate(sampleServer, filesDir = null)
        assertTrue("dns block present", json.contains("\"dns\""))
        assertTrue("bootstrap resolver uses encrypted direct DoH", json.contains("https+local://1.1.1.1/dns-query"))
        assertTrue("proxy endpoint uses bootstrap-only DNS match", json.contains("full:node.example.com"))
        assertTrue("fallback resolver uses routed DoH", json.contains("https://1.1.1.1/dns-query"))
        assertTrue("second routed DoH endpoint is configured", json.contains("https://1.0.0.1/dns-query"))
        assertFalse("plain domestic DNS resolvers must not be configured", json.contains("178.22.122.100"))
        assertFalse("plain secondary DNS resolver must not be configured", json.contains("185.51.200.2"))
        assertFalse("app DNS must not be hijacked by dns-out", json.contains("dns-out"))
        val dnsPortRule = json.indexOf("\"port\": \"53,853,784,8853\"")
        val iranDirectRule = json.indexOf("\"regexp:\\\\.ir$\"")
        val privateDirectRule = json.indexOf("\"10.0.0.0/8\"")
        assertTrue("catch-all DNS transport rule exists", dnsPortRule >= 0)
        assertTrue(
            "DNS transport rule sends TCP and UDP through proxy",
            Regex("""\{\s*"type": "field",\s*"outboundTag": "proxy",\s*"network": "tcp,udp",\s*"port": "53,853,784,8853"\s*}""")
                .containsMatchIn(json)
        )
        assertTrue("DNS rule precedes .ir direct routing", dnsPortRule < iranDirectRule)
        assertTrue("DNS rule precedes private/LAN direct routing", dnsPortRule < privateDirectRule)
        assertTrue("all ordinary TCP/UDP flows use the proxy fallback", json.contains("\"network\": \"tcp,udp\""))
    }

    @Test
    fun `unconfigured private or Iranian DNS endpoints cannot hit direct bypass rules`() {
        val json = XrayConfigGenerator.generate(sampleServer, filesDir = null)
        val dnsPortRule = json.indexOf("\"port\": \"53,853,784,8853\"")
        val iranDirectRule = json.indexOf("\"regexp:\\\\.ir$\"")
        val privateDirectRule = json.indexOf("\"10.0.0.0/8\"")
        assertTrue("DNS rule matches standard DNS and encrypted DNS ports", dnsPortRule >= 0)
        assertTrue("DNS rule is evaluated before Iranian direct rule", dnsPortRule < iranDirectRule)
        assertTrue("DNS rule is evaluated before private-IP direct rule", dnsPortRule < privateDirectRule)
    }

    @Test
    fun `configured private DNS resolver is proxied before private network bypass`() {
        val json = XrayConfigGenerator.generate(
            sampleServer,
            filesDir = null,
            runtimeSettings = RuntimeSettings(dnsServers = listOf("192.168.1.1"))
        )
        val resolverIndex = json.indexOf("\"192.168.1.1\"")
        val privateBypassIndex = json.indexOf("\"10.0.0.0/8\"")
        val proxyRuleIndex = json.lastIndexOf("\"outboundTag\": \"proxy\"", resolverIndex)
        assertTrue("resolver route exists", resolverIndex >= 0)
        assertTrue("resolver is routed through proxy", proxyRuleIndex >= 0 && proxyRuleIndex < resolverIndex)
        assertTrue("resolver proxy rule precedes private direct bypass", resolverIndex < privateBypassIndex)
    }

    @Test
    fun `invalid DNS addresses fall back to numeric public resolvers`() {
        val effectiveResolvers = RuntimeSettings(
            dnsServers = listOf("resolver.example.com", "01.2.3.4", "256.1.1.1")
        ).effectiveDnsServers()
        assertEquals(listOf("1.1.1.1", "1.0.0.1"), effectiveResolvers)
    }

    @Test
    fun `routing strategy is AsIs for low latency`() {
        val json = XrayConfigGenerator.generate(sampleServer, filesDir = null)
        // IPIfNonMatch forced an extra edge-side DNS resolution per connection.
        assertTrue(json.contains("\"domainStrategy\": \"AsIs\""))
        assertFalse(json.contains("IPIfNonMatch"))
    }

    @Test
    fun `geoip is skipped when geoip dat is absent`() {
        // filesDir == null -> hasGeoip == false -> geoip entries omitted.
        val json = XrayConfigGenerator.generate(sampleServer, filesDir = null)
        assertFalse("geoip must be skipped without geoip.dat", json.contains("geoip"))
    }

    @Test
    fun `IP proxy endpoints do not get a hostname bootstrap DNS rule`() {
        val json = XrayConfigGenerator.generate(sampleServer.copy(address = "203.0.113.10"), filesDir = null)
        assertFalse("IP endpoints do not need direct bootstrap DNS", json.contains("https+local://"))
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
