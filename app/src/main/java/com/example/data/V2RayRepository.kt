package com.example.data

import android.util.Base64
import android.content.Context
import com.example.service.RuntimeSettings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLDecoder as JURLDecoder
import java.nio.charset.StandardCharsets

class V2RayRepository(private val db: V2RayDatabase) {

    private val serverDao = db.serverDao()
    private val subscriptionDao = db.subscriptionDao()
    private val logDao = db.logDao()

    val allServers: Flow<List<ServerEntity>> = serverDao.getAllServers()
    val activeServer: Flow<ServerEntity?> = serverDao.getSelectedServerFlow()
    val allSubscriptions: Flow<List<SubscriptionEntity>> = subscriptionDao.getAllSubscriptions()
    val logs: Flow<List<LogEntity>> = logDao.getRecentLogs()

    companion object {
        @Volatile private var settingsPreferences: android.content.SharedPreferences? = null
        fun initializeSettings(context: Context) { settingsPreferences = context.getSharedPreferences("runtime_settings", Context.MODE_PRIVATE) }
    }
    fun getRuntimeSettings(): RuntimeSettings {
        val p = settingsPreferences ?: return RuntimeSettings()
        return RuntimeSettings(
            dnsServers = p.getString("dnsServers", "1.1.1.1")!!.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            routingMode = p.getString("routingMode", "Bypass LAN & Mainland")!!,
            mtu = p.getInt("mtu", 1400),
            httpInboundEnabled = p.getBoolean("httpInboundEnabled", false),
            bypassList = p.getString("bypassList", "")!!.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            autoConnectEnabled = p.getBoolean("autoConnectEnabled", false)
        )
    }
    fun saveRuntimeSettings(settings: RuntimeSettings) { settingsPreferences?.edit()?.putString("dnsServers", settings.dnsServers.joinToString(","))?.putString("routingMode", settings.routingMode)?.putInt("mtu", settings.mtu)?.putBoolean("httpInboundEnabled", settings.httpInboundEnabled)?.putString("bypassList", settings.bypassList.joinToString(","))?.putBoolean("autoConnectEnabled", settings.autoConnectEnabled)?.apply() }

    private fun parseHostPort(value: String, defaultPort: Int): Pair<String, Int> {
        val raw = value.substringBefore('?').substringBefore('#')
        if (raw.startsWith("[")) { val end = raw.indexOf(']'); if (end > 0) return raw.substring(1, end) to raw.substring(end + 1).removePrefix(":").toIntOrNull().let { it ?: defaultPort } }
        val colon = raw.lastIndexOf(':'); return if (colon > 0 && raw.indexOf(':') == colon) raw.substring(0, colon) to (raw.substring(colon + 1).toIntOrNull() ?: defaultPort) else raw to defaultPort
    }

    suspend fun getSelectedServer(): ServerEntity? = serverDao.getSelectedServer()

    suspend fun selectServer(id: Long) {
        serverDao.selectActiveServer(id)
        log("USER", "INFO", "Selected server ID: $id")
    }

    suspend fun addServer(server: ServerEntity): Long {
        val id = serverDao.insertServer(server)
        log("USER", "INFO", "Added server: ${server.name} (${server.address}:${server.port})")
        return id
    }

    suspend fun insertServers(servers: List<ServerEntity>) {
        serverDao.insertServers(servers)
    }

    suspend fun updateServer(server: ServerEntity) {
        serverDao.updateServer(server)
    }

    suspend fun deleteServer(server: ServerEntity) {
        serverDao.deleteServer(server)
        log("USER", "INFO", "Deleted server: ${server.name}")
    }

    suspend fun clearAllServers() {
        serverDao.deleteAllServers()
        log("SYSTEM", "WARNING", "Cleared all server configurations")
    }

    suspend fun addSubscription(name: String, url: String): Long {
        val sub = SubscriptionEntity(name = name, url = url)
        val id = subscriptionDao.insertSubscription(sub)
        log("SUBSCRIPTION", "INFO", "Added subscription: $name - $url")
        return id
    }

    suspend fun deleteSubscription(sub: SubscriptionEntity) {
        subscriptionDao.deleteSubscription(sub)
        log("SUBSCRIPTION", "INFO", "Deleted subscription: ${sub.name}")
    }

    private var logInsertCount = 0

    suspend fun log(tag: String, level: String, message: String) {
        // Avoid logging sensitive tokens directly; mask long tokens
        val safeMessage = message.replace(Regex("[A-Za-z0-9_-]{20,}")) { "[REDACTED]" }
        logDao.insertLog(LogEntity(tag = tag, level = level, message = safeMessage))
        // Keep the table bounded: every 50 inserts prune to the newest 500 rows
        // so a dead-node error flood cannot grow the DB unboundedly.
        if (++logInsertCount % 50 == 0) {
            logDao.pruneLogs(500)
        }
        Log.d("V2RayDan-$tag", "[$level] $safeMessage")
    }

    suspend fun clearLogs() {
        logDao.clearLogs()
    }

    // Ping a server using a real TCP socket connection check!
    suspend fun testServerPing(serverId: Long): Int = withContext(Dispatchers.IO) {
        val server = serverDao.getServerById(serverId) ?: return@withContext -1
        val start = System.currentTimeMillis()
        var pingResult = -1
        var socket: Socket? = null
        try {
            socket = Socket()
            // We use standard handshake timeout of 1200ms
            socket.connect(InetSocketAddress(server.address, server.port), 1200)
            val end = System.currentTimeMillis()
            pingResult = (end - start).toInt()
            serverDao.updatePing(serverId, pingResult)
            log("PING", "SUCCESS", "Ping response from ${server.name}: ${pingResult}ms")
        } catch (e: Exception) {
            log(
                "PING",
                "ERROR",
                "Failed to ping ${server.name} (${server.address}:${server.port}): ${e.localizedMessage}"
            )
            serverDao.updatePing(serverId, -2) // -2 indicates timeout/unreachable
            pingResult = -2
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {
                Log.w("V2RayRepository", "Failed to close socket", e)
            }
        }
        return@withContext pingResult
    }

    // Real subscription import and link parsing!
    suspend fun syncSubscription(subscription: SubscriptionEntity): Int = withContext(Dispatchers.IO) {
        var parsedCount = 0
        var connection: HttpURLConnection? = null
        try {
            log("SUBSCRIPTION", "INFO", "Syncing subscription: ${subscription.name} url: ${subscription.url}")

            // Fetch subscription body
            connection = (URL(subscription.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "v2rayNG/1.8.5 (Android)")
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val rawData: String = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }

                var body = rawData.trim()

                // Heuristic: try Base64 decode, but validate result contains expected schemes or newlines
                val tryDecoded = try {
                    val decodedBytes = Base64.decode(body, Base64.DEFAULT)
                    val decodedStr = String(decodedBytes, StandardCharsets.UTF_8)
                    if (decodedStr.contains("vless://") || decodedStr.contains("vmess://") || decodedStr.contains("ss://") || decodedStr.contains("trojan://") || decodedStr.contains("://")) {
                        decodedStr
                    } else null
                } catch (ex: Exception) {
                    null
                }

                if (tryDecoded != null && tryDecoded.isNotBlank()) {
                    body = tryDecoded
                }

                // Split into lines robustly
                val links = body.split(Regex("\\r?\\n")).map { it.trim() }.filter { it.isNotEmpty() }
                val parsedConfigs = mutableListOf<ServerEntity>()
                for (link in links) {
                    val parsed = parseShareLink(link)
                    if (parsed != null) {
                        parsedConfigs.add(parsed)
                    }
                }

                if (parsedConfigs.isNotEmpty()) {
                    serverDao.insertServers(parsedConfigs)
                    parsedCount = parsedConfigs.size
                    log("SUBSCRIPTION", "SUCCESS", "Successfully imported $parsedCount servers from subscription ${subscription.name}")

                    // Update subscription timestamp
                    subscriptionDao.insertSubscription(subscription.copy(lastUpdated = System.currentTimeMillis()))
                } else {
                    log("SUBSCRIPTION", "WARNING", "No valid V2Ray share links found in the subscription body")
                }
            } else {
                log("SUBSCRIPTION", "ERROR", "Server response error code: $responseCode")
            }
        } catch (e: Exception) {
            log("SUBSCRIPTION", "ERROR", "HTTP load error: ${e.localizedMessage}")
        } finally {
            try {
                connection?.disconnect()
            } catch (ignored: Exception) {
            }
        }
        return@withContext parsedCount
    }

    fun parseShareLink(link: String): ServerEntity? {
        try {
            val trimmed = link.trim()
            if (trimmed.startsWith("vless://", true)) {
                // Format: vless://uuid@host:port?query#name
                val rawBody = trimmed.substringAfter("vless://")
                val atIndex = rawBody.indexOf('@')
                if (atIndex <= 0) return null
                val uuid = rawBody.substring(0, atIndex)

                val hostPortAndQuery = rawBody.substring(atIndex + 1)
                val questionIndex = hostPortAndQuery.indexOf('?')
                val hashIndex = hostPortAndQuery.indexOf('#')

                val hostPort = if (questionIndex != -1) hostPortAndQuery.substring(0, questionIndex) else if (hashIndex != -1) hostPortAndQuery.substring(0, hashIndex) else hostPortAndQuery
                val (address, port) = parseHostPort(hostPort, 443)

                var name = "VLESS Server"
                var paramsStr = ""
                if (questionIndex != -1) {
                    paramsStr = hostPortAndQuery.substring(questionIndex + 1, if (hashIndex != -1) hashIndex else hostPortAndQuery.length)
                }
                if (hashIndex != -1) {
                    name = URLDecoder.decode(hostPortAndQuery.substring(hashIndex + 1))
                }

                val params = if (paramsStr.isNotEmpty()) parseQueryParams(paramsStr) else emptyMap()
                val securityParam = params["security"]?.lowercase() ?: "none"
                val tls = securityParam == "tls" || securityParam == "reality" || params["tls"] == "true"
                val sni = params["sni"] ?: ""
                val host = params["host"] ?: ""
                val network = params["type"] ?: params["network"] ?: "tcp"
                val path = params["path"] ?: ""
                val flow = params["flow"] ?: ""
                val fingerprint = params["fp"] ?: ""
                val publicKey = params["pbk"] ?: ""
                val shortId = params["sid"] ?: ""
                val pinnedCert = params["pcs"] ?: ""
                val spiderX = params["spx"] ?: ""
                val alpn = params["alpn"] ?: ""
                val headerType = params["headerType"] ?: ""
                val grpcServiceName = params["serviceName"] ?: ""

                return ServerEntity(
                    name = name,
                    type = "VLESS",
                    address = address,
                    port = port,
                    uuid = uuid,
                    tls = tls,
                    sni = sni,
                    host = host,
                    network = network,
                    path = path,
                    security = securityParam,
                    flow = flow,
                    fingerprint = fingerprint,
                    publicKey = publicKey,
                    shortId = shortId,
                    pinnedCert = pinnedCert,
                    spiderX = spiderX,
                    alpn = alpn,
                    headerType = headerType,
                    grpcServiceName = grpcServiceName
                )
            } else if (trimmed.startsWith("vmess://", true)) {
                val base64Content = trimmed.substringAfter("vmess://").trim()
                val decoded = try {
                    String(Base64.decode(base64Content, Base64.DEFAULT), StandardCharsets.UTF_8)
                } catch (e: Exception) {
                    return null
                }
                // Parse JSON safely
                val json = try {
                    JSONObject(decoded)
                } catch (e: Exception) {
                    return null
                }
                val ps = json.optString("ps", "VMess Server")
                val add = json.optString("add", "0.0.0.0")
                val port = json.optInt("port", 443)
                val id = json.optString("id", "")
                val aid = json.optInt("aid", 0)
                val net = json.optString("net", "tcp")
                val path = json.optString("path", "")
                val host = json.optString("host", "")
                val tls = json.optString("tls", "") == "tls"
                val sni = json.optString("sni", "")
                val fingerprint = json.optString("fp", "")

                return ServerEntity(
                    name = ps,
                    type = "VMESS",
                    address = add,
                    port = port,
                    uuid = id,
                    alterId = aid,
                    network = net,
                    path = path,
                    host = host,
                    tls = tls,
                    sni = sni,
                    fingerprint = fingerprint
                )
            } else if (trimmed.startsWith("ss://", true)) {
                // ss://base64(method:password)@host:port#name  OR ss://method:password@host:port#name
                val rawBody = trimmed.substringAfter("ss://")
                val hashIndex = rawBody.indexOf('#')
                val mainBody = if (hashIndex != -1) rawBody.substring(0, hashIndex) else rawBody
                val serverName = if (hashIndex != -1) URLDecoder.decode(rawBody.substring(hashIndex + 1)) else "Shadowsocks Server"

                // If mainBody contains '@' then it could be either base64 part before @ or method:pass@host:port
                val atIndex = mainBody.indexOf('@')
                if (atIndex != -1) {
                    val left = mainBody.substring(0, atIndex)
                    val right = mainBody.substring(atIndex + 1)

                    val cryptInfo = try {
                        // try base64 decode left; if fails, treat left as plain method:password
                        val decoded = String(Base64.decode(left, Base64.DEFAULT), StandardCharsets.UTF_8)
                        if (decoded.contains(":")) decoded else left
                    } catch (e: Exception) {
                        left
                    }

                    val (address, port) = parseHostPort(right, 1080)

                    return ServerEntity(
                        name = serverName,
                        type = "SHADOWSOCKS",
                        address = address,
                        port = port,
                        uuid = cryptInfo,
                        tls = false
                    )
                }
            } else if (trimmed.startsWith("trojan://", true)) {
                val rawBody = trimmed.substringAfter("trojan://")
                val atIndex = rawBody.indexOf('@')
                if (atIndex <= 0) return null
                val password = rawBody.substring(0, atIndex)
                val hostPortAndQuery = rawBody.substring(atIndex + 1)
                val questionIndex = hostPortAndQuery.indexOf('?')
                val hashIndex = hostPortAndQuery.indexOf('#')
                val hostPort = if (questionIndex != -1) hostPortAndQuery.substring(0, questionIndex) else if (hashIndex != -1) hostPortAndQuery.substring(0, hashIndex) else hostPortAndQuery

                val (address, port) = parseHostPort(hostPort, 443)

                var name = "Trojan Server"
                var paramsStr = ""
                if (questionIndex != -1) {
                    paramsStr = hostPortAndQuery.substring(questionIndex + 1, if (hashIndex != -1) hashIndex else hostPortAndQuery.length)
                }
                if (hashIndex != -1) {
                    name = URLDecoder.decode(hostPortAndQuery.substring(hashIndex + 1))
                }

                val params = if (paramsStr.isNotEmpty()) parseQueryParams(paramsStr) else emptyMap()
                val tls = params["security"]?.lowercase() != "none" && params["tls"]?.lowercase() != "false"
                val sni = params["sni"] ?: ""
                val network = params["type"] ?: params["network"] ?: "tcp"
                val path = params["path"] ?: ""
                val host = params["host"] ?: ""
                val fingerprint = params["fp"] ?: ""
                val alpn = params["alpn"] ?: ""
                val grpcServiceName = params["serviceName"] ?: ""
                val security = params["security"] ?: if (tls) "tls" else "none"

                return ServerEntity(
                    name = name,
                    type = "TROJAN",
                    address = address,
                    port = port,
                    uuid = password,
                    tls = tls,
                    sni = sni,
                    security = security,
                    network = network,
                    path = path,
                    host = host,
                    fingerprint = fingerprint,
                    alpn = alpn,
                    grpcServiceName = grpcServiceName
                )
            }
        } catch (e: Exception) {
            Log.e("V2RayRepository", "Error parsing share link: $link", e)
        }
        return null
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val params = mutableMapOf<String, String>()
        val pairs = query.split("&")
        for (pair in pairs) {
            val idx = pair.indexOf("=")
            if (idx != -1) {
                val key = URLDecoder.decode(pair.substring(0, idx))
                val value = URLDecoder.decode(pair.substring(idx + 1))
                params[key] = value
            } else if (pair.isNotEmpty()) {
                params[URLDecoder.decode(pair)] = ""
            }
        }
        return params
    }

    private fun getValueFromJson(json: String, key: String): String? {
        // Keep for backward compatibility but prefer JSONObject
        return try {
            val obj = JSONObject(json)
            if (obj.has(key)) obj.optString(key) else null
        } catch (e: Exception) {
            null
        }
    }
}

// Inline helper for platform URLDecoder
object URLDecoder {
    fun decode(s: String): String {
        return try {
            JURLDecoder.decode(s, "UTF-8")
        } catch (e: Exception) {
            s
        }
    }
}
