package com.example.service

import com.example.data.ServerEntity
import java.io.File

object XrayConfigGenerator {

    // JSON-safe string interpolation. Every string value from a user-supplied
    // share link is interpolated into the config JSON by string templating, so a
    // single stray " or \ in address/path/sni/publicKey/etc. would corrupt the
    // whole document and make Xray fail to parse it at startup (which shows up as
    // "connects then immediately disconnects"). Escape them all instead of
    // trusting the input.
    private fun str(value: String): String {
        val sb = StringBuilder("\"")
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') {
                    sb.append(String.format("\\u%04x", ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    // Builds a JSON string array for ALPN from a comma-separated raw value,
    // dropping empty entries (a link like "alpn=h2,http/1.1," would otherwise
    // produce ["h2","http/1.1",""] which Xray rejects).
    private fun alpnArray(rawAlpn: String): String {
        val entries = rawAlpn
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (entries.isEmpty()) return ""
        return entries.joinToString(",", prefix = "[", postfix = "]") { str(it) }
    }

    const val SOCKS_INBOUND_PORT = 10808
    private const val HTTP_INBOUND_PORT = 10809

    // Xray-core has no "tun" inbound protocol — confirmed against the official
    // Xray-core source and infra/conf parser (unknown config / exit code 23 if
    // attempted). TUN termination is handled entirely outside Xray by
    // hev-socks5-tunnel, which reads/writes the raw TUN fd and forwards traffic
    // into the "socks-in" inbound below over loopback. Xray only ever sees
    // ordinary SOCKS5 connections on 127.0.0.1:10808 — it has no awareness of
    // the VPN/TUN layer at all.
    // (helpers str()/alpnArray() above produce the JSON strings used below.)

    fun generate(
        server: ServerEntity,
        filesDir: File? = null,
        socksPort: Int = SOCKS_INBOUND_PORT,
        httpPort: Int = HTTP_INBOUND_PORT,
        runtimeSettings: RuntimeSettings = RuntimeSettings()
    ): String {
        val outbounds = when (server.type.uppercase()) {
            "VLESS" -> generateVlessOutbound(server, runtimeSettings)
            "VMESS" -> generateVmessOutbound(server, runtimeSettings)
            "TROJAN" -> generateTrojanOutbound(server, runtimeSettings)
            "SHADOWSOCKS" -> generateShadowsocksOutbound(server, runtimeSettings)
            else -> generateFreedomOutbound()
        }
        val fragmentOutboundJson = if (shouldUseFragment(server, runtimeSettings)) {
            generateFragmentOutbound(server, runtimeSettings)
        } else {
            ""
        }

        // Safety verification: only append geoip strings if the geoip.dat file actually
        // exists, to prevent a core crash from a missing/corrupt file.
        //
        // NOTE: We intentionally do NOT reference "geosite:ir" here. The geosite.dat
        // bundled with official Xray-core releases does not include an "ir" domain
        // category (confirmed: https://github.com/XTLS/Xray-core/issues/1406 —
        // "we couldn't use category-ir in Iran using xray"). Referencing it causes
        // Xray-core to fail parsing the config at startup:
        //   "infra/conf: failed to parse ...domain rule: geosite:ir"
        // The regexp rules below already cover .ir domains without needing that category.
        val hasGeoip = filesDir != null && File(filesDir, "geoip.dat").exists()

        // App DNS packets stay ordinary port-53 flows and are explicitly routed
        // through the proxy before any direct-bypass rule; they are not handed
        // to dns-out and resolved on the physical network. Xray itself needs
        // DNS to bootstrap a domain-based proxy endpoint. Resolve that endpoint with direct DoH
        // over TLS to a numeric resolver IP, avoiding plaintext DNS and a
        // proxy/DNS circular dependency. Other Xray DNS lookups use routed DoH.
        val bootstrapDnsServer = if (isIpLiteral(server.address)) {
            null
        } else {
            """
            {
              "address": "https+local://1.1.1.1/dns-query",
              "domains": [${str("full:${server.address}")}],
              "skipFallback": true
            }
            """.trimIndent()
        }
        val dnsServers = listOfNotNull(
            bootstrapDnsServer,
            str("https://1.1.1.1/dns-query"),
            str("https://1.0.0.1/dns-query")
        ).joinToString(",\n")
        val dnsBlock = """
          "dns": {
            "queryStrategy": "UseIPv4",
            "servers": [$dnsServers]
          },
        """.trimIndent()

        // Route DNS transports through the proxy before any domain/IP bypass.
        // Matching only the resolver configured in VpnService is insufficient:
        // apps can send DNS to their own resolver, and a private or Iranian DNS
        // address would otherwise match a direct-bypass rule. DoH on HTTPS/443
        // is indistinguishable from ordinary HTTPS at the port level; it follows
        // the normal domain/proxy routing policy below.
        val dnsTransportProxyRule = """
          {
            "type": "field",
            "outboundTag": "proxy",
            "network": "tcp,udp",
            "port": "53,853,784,8853"
          }
        """.trimIndent()

        // Keep every resolver advertised by VpnService on the proxy path,
        // including private/LAN addresses. Route all traffic to these exact
        // resolver IPs through proxy even when its destination port is not a
        // standard DNS port.
        val dnsResolverIps = runtimeSettings.effectiveDnsServers().joinToString(",") { str(it) }
        val dnsProxyRule = """
          {
            "type": "field",
            "outboundTag": "proxy",
            "ip": [$dnsResolverIps]
          }
        """.trimIndent()

        // CRITICAL FIX: hev-socks5-tunnel hands Xray raw destination IPs for
        // every connection (the TUN layer sees IP packets; hev passes the IP
        // literal through SOCKS). With sniffing disabled Xray never recovers
        // the TLS SNI / HTTP Host / QUIC SNI, so the "*.ir goes direct" DOMAIN
        // rule could NEVER match — every Iranian site was pulled through the
        // overseas proxy. That is the root cause of the reported
        // "connected but slow / some sites never load" behaviour.
        //
        // Enabling sniffing (with http+tls+quic override) restores the original
        // hostname from the handshake so domain routing actually works. quic is
        // needed for HTTP/3 traffic (Google/YouTube over UDP/443); UDP is
        // already enabled on the inbound.
        //
        // domainStrategy is "AsIs": Xray does not initiate an extra IP-based
        // DNS lookup just to route each connection. The app's resolver queries
        // are routed through the proxy, while sniffing recovers hostnames so
        // the .ir domain rule can select the direct outbound. "IPIfNonMatch"
        // forced an extra resolution for unmatched connections.
        //
        // Do not route app port-53 traffic to dns-out: that would make Xray
        // issue resolver requests outside the VPN app's own tunnel. TUN DNS
        // flows remain ordinary SOCKS UDP/TCP traffic, but the first routing
        // rule forces standard DNS/DoT/DoQ ports through proxy before bypasses.
        val routingRules = """
          "routing": {
            "domainStrategy": "AsIs",
            "rules": [
              $dnsTransportProxyRule,
              $dnsProxyRule,
              {
                "type": "field",
                "outboundTag": "direct",
                "domain": [
                  "regexp:\\.ir$",
                  "regexp:^[^.]*\\.ir$"
                ]
              },
              {
                "type": "field",
                "outboundTag": "direct",
                "ip": [
                  "10.0.0.0/8",
                  "172.16.0.0/12",
                  "192.168.0.0/16",
                  "127.0.0.0/8",
                  "100.64.0.0/10",
                  "fc00::/7",
                  "fe80::/10"
                  ${if (hasGeoip) ",\"geoip:private\",\"geoip:ir\"" else ""}
                ]
              },
              {
                "type": "field",
                "outboundTag": "proxy",
                "network": "tcp,udp"
              }
            ]
          },
        """.trimIndent()

        return """
        {
          "log": {
            "loglevel": "warning"
          },
          $dnsBlock
          $routingRules
          "inbounds": [
            {
              "tag": "socks-in",
              "port": $socksPort,
              "listen": "127.0.0.1",
              "protocol": "socks",
              "settings": {
                "auth": "noauth",
                "udp": true
              },
              "sniffing": {
                "enabled": true,
                "destOverride": ["http", "tls", "quic"]
              }
            },
            {
              "tag": "http-in",
              "port": $httpPort,
              "listen": "127.0.0.1",
              "protocol": "http",
              "settings": {},
              "sniffing": {
                "enabled": true,
                "destOverride": ["http", "tls", "quic"]
              }
            }
          ],
          "outbounds": [
            $outbounds${if (fragmentOutboundJson.isNotEmpty()) ",\n$fragmentOutboundJson" else ""},
            {
              "protocol": "freedom",
              "settings": {},
              "tag": "direct"
            }
          ]
        }
        """.trimIndent()
    }

    private fun isIpLiteral(address: String): Boolean {
        if (address.contains(':')) return true
        val octets = address.split('.')
        return octets.size == 4 && octets.all { octet ->
            octet.toIntOrNull()?.let { it in 0..255 } == true
        }
    }

    private fun generateVlessOutbound(server: ServerEntity, runtimeSettings: RuntimeSettings): String {
        val streamSettingsJson = generateStreamSettings(server, runtimeSettings)
        // flow comes from the link as-is (v2rayNG behaviour). Auto-defaulting to
        // xtls-rprx-vision breaks servers whose REALITY expects no flow: the auth
        // fails silently and the server falls back to its real SNI target.
        val flowValue = server.flow
        return """
        {
          "protocol": "vless",
          "settings": {
            "address": ${str(server.address)},
            "port": ${server.port},
            "id": ${str(server.uuid)},
            "encryption": "none",
            "flow": ${str(flowValue)},
            "level": 0
          },
          "streamSettings": $streamSettingsJson,
          "tag": "proxy"
        }
        """
    }

    private fun generateVmessOutbound(server: ServerEntity, runtimeSettings: RuntimeSettings): String {
        val streamSettingsJson = generateStreamSettings(server, runtimeSettings)
        return """
        {
          "protocol": "vmess",
          "settings": {
            "address": ${str(server.address)},
            "port": ${server.port},
            "id": ${str(server.uuid)},
            "security": ${str(server.security.ifEmpty { "auto" })},
            "level": 0
          },
          "streamSettings": $streamSettingsJson,
          "tag": "proxy"
        }
        """
    }

    private fun generateTrojanOutbound(server: ServerEntity, runtimeSettings: RuntimeSettings): String {
        val streamSettingsJson = generateStreamSettings(server, runtimeSettings)
        return """
        {
          "protocol": "trojan",
          "settings": {
            "address": ${str(server.address)},
            "port": ${server.port},
            "password": ${str(server.uuid)},
            "level": 0
          },
          "streamSettings": $streamSettingsJson,
          "tag": "proxy"
        }
        """
    }

    private fun generateShadowsocksOutbound(server: ServerEntity, runtimeSettings: RuntimeSettings): String {
        val creds = server.uuid.split(":")
        val method = if (creds.isNotEmpty()) creds[0] else "aes-256-gcm"
        val password = if (creds.size > 1) creds[1] else "mypassword"
        val streamSettingsJson = generateStreamSettings(server, runtimeSettings)

        return """
        {
          "protocol": "shadowsocks",
          "settings": {
            "address": ${str(server.address)},
            "port": ${server.port},
            "method": ${str(method)},
            "password": ${str(password)},
            "level": 0
          },
          "streamSettings": $streamSettingsJson,
          "tag": "proxy"
        }
        """
    }

    private fun generateFreedomOutbound(): String {
        return """
        {
          "protocol": "freedom",
          "settings": {},
          "tag": "proxy"
        }
        """
    }

    private fun shouldUseFragment(server: ServerEntity, runtimeSettings: RuntimeSettings): Boolean {
        val supportedProtocol = server.type.uppercase() in setOf("VLESS", "VMESS", "TROJAN", "SHADOWSOCKS")
        val hasTls = server.tls || server.security.equals("reality", ignoreCase = true)
        return runtimeSettings.fragmentEnabled && supportedProtocol && hasTls
    }

    private fun generateFragmentOutbound(server: ServerEntity, runtimeSettings: RuntimeSettings): String {
        val isReality = server.security.equals("reality", ignoreCase = true)
        val requestedPackets = runtimeSettings.fragmentPackets.trim().lowercase()
        val packets = when {
            requestedPackets == "tlshello" && isReality -> "1-3"
            requestedPackets == "tlshello" -> "tlshello"
            requestedPackets.matches(Regex("\\d+-\\d+")) ->
                normalizeRange(requestedPackets, fallback = "1-3", minValue = 1, maxValue = 100)
            else -> "1-3"
        }
        val length = normalizeRange(runtimeSettings.fragmentLength, "50-100", 1, 65_535)
        val interval = normalizeRange(runtimeSettings.fragmentInterval, "10-20", 0, 60_000)
        val maxSplit = normalizeRange(runtimeSettings.fragmentMaxSplit, "10", 1, 1_024)

        return """
        {
          "protocol": "freedom",
          "settings": {
            "fragment": {
              "packets": ${str(packets)},
              "length": ${str(length)},
              "interval": ${str(interval)},
              "maxSplit": ${str(maxSplit)}
            }
          },
          "tag": "fragment"
        }
        """.trimIndent()
    }

    private fun normalizeRange(raw: String, fallback: String, minValue: Int, maxValue: Int): String {
        val pieces = raw.trim().split('-')
        if (pieces.size !in 1..2) return fallback
        val from = pieces[0].trim().toIntOrNull() ?: return fallback
        val to = if (pieces.size == 1) from else pieces[1].trim().toIntOrNull() ?: return fallback
        if (from !in minValue..maxValue || to !in from..maxValue) return fallback
        return if (pieces.size == 1) from.toString() else "$from-$to"
    }

    private fun generateStreamSettings(server: ServerEntity, runtimeSettings: RuntimeSettings): String {
        val isReality = server.security.lowercase() == "reality"
        val securityStr = when {
            isReality -> "reality"
            server.tls -> "tls"
            else -> "none"
        }

        // Normalize transport names to the values Xray-core actually accepts.
        // Using an alias ("mkcp") or a bogus value in the "network" field makes
        // Xray fail to parse the config and exit right at startup — which shows
        // up as "connects then immediately disconnects" on those node types.
        // Keep the actual json output as h2/http2, kcp, ... valid names.
        fun normalizedNetwork(raw: String): String = when (raw.lowercase()) {
            "mkcp", "kcp" -> "kcp"
            "h2" -> "h2"
            else -> raw.lowercase().ifEmpty { "tcp" }
        }

        val securityConfig = when {
            isReality -> {
                val sniToUse = server.sni.ifEmpty { "www.google.com" }
                val fingerprintToUse = server.fingerprint.ifEmpty { "chrome" }
                val spiderXToUse = server.spiderX.ifEmpty { "/" }
                """
                "realitySettings": {
                  "show": false,
                  "fingerprint": ${str(fingerprintToUse)},
                  "serverName": ${str(sniToUse)},
                  "password": ${str(server.publicKey)},
                  "shortId": ${str(server.shortId)},
                  "spiderX": ${str(spiderXToUse)}
                }
                """
            }
            server.tls -> {
                val sniToUse = server.sni.ifEmpty { server.address }
                // Always send a browser TLS fingerprint: with none, xray uses Go's
                // default TLS stack and Cloudflare-fronted nodes RST it (JA3). v2rayNG
                // always sends a fingerprint (default chrome) - match that.
                val fingerprintToUse = server.fingerprint.ifEmpty { "chrome" }
                val tlsParts = mutableListOf<String>()
                tlsParts.add("\"serverName\": ${str(sniToUse)}")
                tlsParts.add("\"fingerprint\": ${str(fingerprintToUse)}")
                if (server.alpn.isNotBlank()) {
                    val alpnArr = alpnArray(server.alpn)
                    if (alpnArr.isNotEmpty()) {
                        tlsParts.add("\"alpn\": $alpnArr")
                    }
                }
                if (server.pinnedCert.isNotBlank()) {
                    tlsParts.add("\"pinnedPeerCertSha256\": ${str(server.pinnedCert)}")
                }
                """
                "tlsSettings": {
                  ${tlsParts.joinToString(",\n      ")}
                }
                """
            }
            else -> ""
        }

        val transportConfig = when (server.network.lowercase()) {
            "ws" -> """
            "wsSettings": {
              "path": ${str(server.path.ifEmpty { "/" })},
              "host": ${str(server.host.ifEmpty { server.address })}
            }
            """
            "xhttp", "splithttp" -> """
            "xhttpSettings": {
              "path": ${str(server.path.ifEmpty { "/" })},
              "host": ${str(server.host.ifEmpty { server.address })},
              "mode": "auto"
            }
            """
            "grpc" -> """
            "grpcSettings": {
              "serviceName": ${str(server.grpcServiceName.ifEmpty { server.path }.ifEmpty { "v2ray-grpc" })}
            }
            """
            "kcp", "mkcp" -> """
            "kcpSettings": {}
            """
            "httpupgrade" -> """
            "httpupgradeSettings": {
              "path": ${str(server.path.ifEmpty { "/" })},
              "host": ${str(server.host.ifEmpty { server.address })}
            }
            """
            else -> ""
        }

        val parts = mutableListOf<String>()
        if (securityConfig.isNotEmpty()) parts.add(securityConfig)
        if (transportConfig.isNotEmpty()) parts.add(transportConfig)
        if (shouldUseFragment(server, runtimeSettings)) {
            parts.add("\"sockopt\": {\"dialerProxy\": \"fragment\"}")
        }

        return """
        {
          "network": "${normalizedNetwork(server.network)}",
          "security": "$securityStr"
          ${if (parts.isNotEmpty()) "," else ""}
          ${parts.joinToString(",")}
        }
        """
    }
}
