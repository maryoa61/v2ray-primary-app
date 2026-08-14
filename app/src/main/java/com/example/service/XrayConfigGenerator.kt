package com.example.service

import com.example.data.ServerEntity
<<<<<<< HEAD
import com.example.service.RuntimeSettings
=======
>>>>>>> 81099166748c1091a99f14777d37940c6ca17c63
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

    // Xray-core has no "tun" inbound protocol — confirmed against the official
    // Xray-core source and infra/conf parser (unknown config / exit code 23 if
    // attempted). TUN termination is handled entirely outside Xray by
    // hev-socks5-tunnel, which reads/writes the raw TUN fd and forwards traffic
    // into the "socks-in" inbound below over loopback. Xray only ever sees
    // ordinary SOCKS5 connections on 127.0.0.1:10808 — it has no awareness of
    // the VPN/TUN layer at all.
    // (helpers str()/alpnArray() above produce the JSON strings used below.)

<<<<<<< HEAD
    fun generate(server: ServerEntity, filesDir: File? = null, runtimeSettings: RuntimeSettings = RuntimeSettings()): String {
=======
    fun generate(server: ServerEntity, filesDir: File? = null): String {
>>>>>>> 81099166748c1091a99f14777d37940c6ca17c63
        val outbounds = when (server.type.uppercase()) {
            "VLESS" -> generateVlessOutbound(server)
            "VMESS" -> generateVmessOutbound(server)
            "TROJAN" -> generateTrojanOutbound(server)
            "SHADOWSOCKS" -> generateShadowsocksOutbound(server)
            else -> generateFreedomOutbound()
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

<<<<<<< HEAD
        val routingRules = when (runtimeSettings.routingMode.lowercase()) {
            "global proxy", "global" -> """"routing": { "domainStrategy": "AsIs", "rules": [{"type":"field","outboundTag":"proxy","network":"tcp,udp"}] },"""
            "direct routing", "direct" -> """"routing": { "domainStrategy": "AsIs", "rules": [{"type":"field","outboundTag":"direct","network":"tcp,udp"}] },"""
            else -> {
                val bypassDomains = if (runtimeSettings.bypassList.isEmpty()) "regexp:\\\\.ir$" else runtimeSettings.bypassList.joinToString(",") { str(it) }
                """"routing": { "domainStrategy": "IPIfNonMatch", "rules": [{"type":"field","outboundTag":"direct","domain":[${bypassDomains}]},{"type":"field","outboundTag":"direct","ip":["10.0.0.0/8","172.16.0.0/12","192.168.0.0/16","127.0.0.0/8","fc00::/7","fe80::/10"]},{"type":"field","outboundTag":"proxy","network":"tcp,udp"}] },"""
            }
        }
=======
        val routingRules = """
          "routing": {
            "domainStrategy": "IPIfNonMatch",
            "rules": [
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

>>>>>>> 81099166748c1091a99f14777d37940c6ca17c63
        return """
        {
          "log": {
            "loglevel": "warning"
          },
          $routingRules
          "inbounds": [
            {
              "tag": "socks-in",
              "port": $SOCKS_INBOUND_PORT,
              "listen": "127.0.0.1",
              "protocol": "socks",
              "settings": {
                "auth": "noauth",
                "udp": true
              },
              "sniffing": {
                "enabled": false,
                "destOverride": ["http", "tls"]
              }
            },
<<<<<<< HEAD
            ${if (runtimeSettings.httpInboundEnabled) """,
            { "port": 10809, "listen": "127.0.0.1", "protocol": "http", "settings": {}, "sniffing": { "enabled": false, "destOverride": ["http", "tls"] } }
            """ else ""}
=======
            {
              "port": 10809,
              "listen": "127.0.0.1",
              "protocol": "http",
              "settings": {},
              "sniffing": {
                "enabled": false,
                "destOverride": ["http", "tls"]
              }
            }
>>>>>>> 81099166748c1091a99f14777d37940c6ca17c63
          ],
          "outbounds": [
            $outbounds,
            {
              "protocol": "freedom",
              "settings": {},
              "tag": "direct"
            }
          ]
        }
        """.trimIndent()
    }

    private fun generateVlessOutbound(server: ServerEntity): String {
        val streamSettingsJson = generateStreamSettings(server)
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

    private fun generateVmessOutbound(server: ServerEntity): String {
        val streamSettingsJson = generateStreamSettings(server)
        return """
        {
          "protocol": "vmess",
          "settings": {
            "address": ${str(server.address)},
            "port": ${server.port},
            "id": ${str(server.uuid)},
            "security": ${str(server.security.ifEmpty { "auto" })},
<<<<<<< HEAD
            "alterId": ${server.alterId},
=======
>>>>>>> 81099166748c1091a99f14777d37940c6ca17c63
            "level": 0
          },
          "streamSettings": $streamSettingsJson,
          "tag": "proxy"
        }
        """
    }

    private fun generateTrojanOutbound(server: ServerEntity): String {
        val streamSettingsJson = generateStreamSettings(server)
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

    private fun generateShadowsocksOutbound(server: ServerEntity): String {
        val creds = server.uuid.split(":")
        val method = if (creds.isNotEmpty()) creds[0] else "aes-256-gcm"
        val password = if (creds.size > 1) creds[1] else "mypassword"
        val streamSettingsJson = generateStreamSettings(server)

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

    private fun generateStreamSettings(server: ServerEntity): String {
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
<<<<<<< HEAD
            "h2", "http2" -> "h2"
            "splithttp" -> "xhttp"
=======
            "h2" -> "h2"
>>>>>>> 81099166748c1091a99f14777d37940c6ca17c63
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
<<<<<<< HEAD
            "tcp" -> if (server.headerType.isNotBlank()) """
            "tcpSettings": { "header": { "type": ${str(server.headerType)} } }
            """ else ""
=======
>>>>>>> 81099166748c1091a99f14777d37940c6ca17c63
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
