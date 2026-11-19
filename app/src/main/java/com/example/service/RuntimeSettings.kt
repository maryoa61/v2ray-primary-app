package com.example.service

data class RuntimeSettings(
    val dnsServers: List<String> = listOf("1.1.1.1", "1.0.0.1"),
    val routingMode: String = "Bypass LAN & Mainland",
    val mtu: Int = 1400,
    val httpInboundEnabled: Boolean = false,
    val bypassList: List<String> = emptyList(),
    val autoConnectEnabled: Boolean = false,
    val fragmentEnabled: Boolean = false,
    val fragmentPackets: String = "tlshello",
    val fragmentLength: String = "50-100",
    val fragmentInterval: String = "10-20",
    val fragmentMaxSplit: String = "10"
)

/** Numeric IPv4 resolvers that can be advertised without triggering system DNS lookup. */
fun RuntimeSettings.effectiveDnsServers(): List<String> {
    val configured = dnsServers.mapNotNull(::canonicalIpv4).distinct()
    return configured.ifEmpty { listOf("1.1.1.1", "1.0.0.1") }
}

private fun canonicalIpv4(raw: String): String? {
    val parts = raw.trim().split('.')
    if (parts.size != 4) return null

    val octets = mutableListOf<Int>()
    for (part in parts) {
        if (part.isEmpty() || (part.length > 1 && part.startsWith('0'))) return null
        val octet = part.toIntOrNull() ?: return null
        if (octet !in 0..255) return null
        octets.add(octet)
    }
    return octets.joinToString(".")
}
