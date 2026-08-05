package com.example.service

import com.example.data.ServerEntity
import java.io.File

object XrayConfigGenerator {

    const val SOCKS_INBOUND_PORT = 10808
    const val HTTP_INBOUND_PORT = 10809

    fun generate(server: ServerEntity, filesDir: File? = null): String {
        val outbounds = when (server.type.uppercase()) {
            "VLESS" -> generateVlessOutbound(server)
            "VMESS" -> generateVmessOutbound(server)
            "TROJAN" -> generateTrojanOutbound(server)
            "SHADOWSOCKS" -> generateShadowsocksOutbound(server)
            else -> generateFreedomOutbound()
        }

        val hasGeoip = filesDir != null && File(filesDir, "geoip.dat").exists()

        val routingRules = """
          "dns": {
            "servers": [
              "1.1.1.1",
              "8.8.8.8",
              {
                "address": "223.5.5.5",
                "domains": ["geosite:cn", "ntp.org", "domain:ir"]
              }
            ]
          },
          "routing": {
            "domainStrategy": "AsIs",
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
                "enabled": true,
                "destOverride": ["http", "tls", "quic"]
              }
            },
            {
              "tag": "http-in",
              "port": $HTTP_INBOUND_PORT,
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
        val flowValue = server.flow.ifEmpty {
            if (server.security.lowercase() == "reality") "xtls-rprx-vision" else ""
        }
        return """
        {
          "protocol": "vless",
          "settings": {
            "vnext": [
              {
                "address": "${server.address}",
                "port": ${server.port},
                "users": [
                  {
                    "id": "${server.uuid}",
                    "encryption": "none",
                    "flow": "$flowValue",
                    "level": 0
                  }
                ]
              }
            ]
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
            "vnext": [
              {
                "address": "${server.address}",
                "port": ${server.port},
                "users": [
                  {
                    "id": "${server.uuid}",
                    "alterId": ${server.alterId},
                    "security": "${server.security.ifEmpty { "auto" }}",
                    "level": 0
                  }
                ]
              }
            ]
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
            "servers": [
              {
                "address": "${server.address}",
                "port": ${server.port},
                "password": "${server.uuid}",
                "level": 0
              }
            ]
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
            "servers": [
              {
                "address": "${server.address}",
                "port": ${server.port},
                "method": "$method",
                "password": "$password",
                "level": 0
              }
            ]
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

        val securityConfig = when {
            isReality -> {
                val sniToUse = server.sni.ifEmpty { "www.google.com" }
                val fingerprintToUse = server.fingerprint.ifEmpty { "chrome" }
                """
                "realitySettings": {
                  "show": false,
                  "fingerprint": "$fingerprintToUse",
                  "serverName": "$sniToUse",
                  "publicKey": "${server.publicKey}",
                  "shortId": "${server.shortId}",
                  "spiderX": "/"
                }
                """
            }
            server.tls -> {
                val sniToUse = server.sni.ifEmpty { server.address }
                """
                "tlsSettings": {
                  "serverName": "$sniToUse",
                  "allowInsecure": false
                }
                """
            }
            else -> ""
        }

        val transportConfig = when (server.network.lowercase()) {
            "ws" -> """
            "wsSettings": {
              "path": "${server.path.ifEmpty { "/" }}",
              "headers": {
                "Host": "${server.host.ifEmpty { server.address }}"
              }
            }
            """
            "grpc" -> """
            "grpcSettings": {
              "serviceName": "${server.path.ifEmpty { "v2ray-grpc" }}"
            }
            """
            else -> ""
        }

        val parts = mutableListOf<String>()
        if (securityConfig.isNotEmpty()) parts.add(securityConfig)
        if (transportConfig.isNotEmpty()) parts.add(transportConfig)

        return """
        {
          "network": "${server.network.lowercase().ifEmpty { "tcp" }}",
          "security": "$securityStr"
          ${if (parts.isNotEmpty()) "," else ""}
          ${parts.joinToString(", ")}
        }
        """
    }
}
