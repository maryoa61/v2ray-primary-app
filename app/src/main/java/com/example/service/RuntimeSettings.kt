package com.example.service

/** Runtime VPN settings shared by the UI, VpnService.Builder, and Xray config. */
data class RuntimeSettings(
    val dnsServers: List<String> = listOf("1.1.1.1"),
    val routingMode: String = "Bypass LAN & Mainland",
    val mtu: Int = 1400,
    val httpInboundEnabled: Boolean = false,
    val bypassList: List<String> = emptyList(),
    val autoConnectEnabled: Boolean = false
)
