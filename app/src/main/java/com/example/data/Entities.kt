package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String, // VMESS, VLESS, SHADOWSOCKS, TROJAN
    val address: String,
    val port: Int,
    val uuid: String,
    val alterId: Int = 0,
    val security: String = "auto",
    val network: String = "tcp", // tcp, ws, grpc, h2
    val path: String = "",
    val host: String = "",
    val sni: String = "",
    val tls: Boolean = false,
    val flow: String = "",
    val fingerprint: String = "",
    val publicKey: String = "",
    val shortId: String = "",
    val pinnedCert: String = "",
    val spiderX: String = "",
    val alpn: String = "",
    val headerType: String = "",
    val grpcServiceName: String = "",
    val ping: Int? = null,
    val isSelected: Boolean = false
) {
    fun toLink(): String {
        return when (type.uppercase()) {
            "VMESS" -> "vmess://$address:$port?network=$network&path=$path&host=$host&tls=$tls"
            "VLESS" -> "vless://$uuid@$address:$port?network=$network&path=$path&host=$host&security=${if (tls) "tls" else "none"}&sni=$sni${if (pinnedCert.isNotBlank()) "&pcs=$pinnedCert" else ""}"
            "SHADOWSOCKS" -> "ss://$uuid@$address:$port"
            "TROJAN" -> "trojan://$uuid@$address:$port?sni=$sni&tls=$tls"
            else -> "$name ($address:$port)"
        }
    }
}

@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val lastUpdated: Long = System.currentTimeMillis()
)

@Entity(tableName = "logs")
data class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val tag: String = "V2RAY",
    val level: String = "INFO", // INFO, WARNING, ERROR, SUCCESS
    val message: String
)
