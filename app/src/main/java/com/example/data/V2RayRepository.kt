package com.example.data

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.service.RuntimeSettings
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
        @Volatile private var preferences: android.content.SharedPreferences? = null
        fun initializeSettings(context: Context) { preferences = context.getSharedPreferences("runtime_settings", Context.MODE_PRIVATE) }
    }
    fun getRuntimeSettings(): RuntimeSettings {
        val p = preferences ?: return RuntimeSettings()
        return RuntimeSettings(
            dnsServers = p.getString("dnsServers", "1.1.1.1")!!.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            routingMode = p.getString("routingMode", "Bypass LAN & Mainland")!!,
            mtu = p.getInt("mtu", 1400),
            httpInboundEnabled = p.getBoolean("httpInboundEnabled", false),
            bypassList = p.getString("bypassList", "")!!.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            autoConnectEnabled = p.getBoolean("autoConnectEnabled", false)
        )
    }
    fun saveRuntimeSettings(settings: RuntimeSettings) { preferences?.edit()?.putString("dnsServers", settings.dnsServers.joinToString(","))?.putString("routingMode", settings.routingMode)?.putInt("mtu", settings.mtu)?.putBoolean("httpInboundEnabled", settings.httpInboundEnabled)?.putString("bypassList", settings.bypassList.joinToString(","))?.putBoolean("autoConnectEnabled", settings.autoConnectEnabled)?.apply() }

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
                    // FIX: OnConflictStrategy.REPLACE keys on the auto-generated
                    // row id, which is 0 for freshly parsed links, so every
                    // sync INSERTED a brand-new row. Re-syncing a subscription
                    // (or auto-sync + manual sync) created unlimited duplicate
                    // entries for the same node — the list grew on every
                    // refresh, the background auto-connector pinged every copy
                    // every 20s (battery/data drain), and the user saw dozens
                    // of identical profiles. Upsert on the natural key instead.
                    val existing = serverDao.getAllServersOnce()
                    val deduped = dedupeAgainstExisting(parsedConfigs, existing)
                    if (deduped.isNotEmpty()) {
                        serverDao.insertServers(deduped)
                    }
                    parsedCount = deduped.size
                    val duplicatesSkipped = parsedConfigs.size - deduped.size
                    log(
                        "SUBSCRIPTION",
                        "SUCCESS",
                        "Sync finished for ${subscription.name}: $parsedCount new/updated server(s)" +
                                if (duplicatesSkipped > 0) ", $duplicatesSkipped duplicate(s) skipped" else ""
                    )

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
                val address: String
                val port: Int
                if (hostPort.contains(':')) {
                    val parts = hostPort.split(":", limit = 2)
                    address = parts[0]
                    port = parts.getOrNull(1)?.toIntOrNull() ?: 443
                } else {
                    address = hostPort
                    port = 443
                }

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
                // FIX: some exporters (and hand-written links) encode "port" as
                // a JSON string ("443"). optInt() returns 0/default for a
                // String value, so the client tried port 0 -> immediate
                // connect failure. Parse the raw token and coerce both forms.
                val port = (json.opt("port")?.toString()?.trim()?.toIntOrNull() ?: 443)
                    .coerceIn(1, 65535)
                val id = json.optString("id", "")
                val aid = json.opt("aid")?.toString()?.trim()?.toIntOrNull() ?: 0
                val net = json.optString("net", "tcp")
                val path = json.optString("path", "")
                val host = json.optString("host", "")
                val tlsRaw = json.optString("tls", "")
                val tls = tlsRaw.equals("tls", ignoreCase = true)
                val sni = json.optString("sni", "")
                val fingerprint = json.optString("fp", "")
                val scy = json.optString("scy", "").ifEmpty { "auto" }
                val alpn = json.optString("alpn", "")

                return ServerEntity(
                    name = ps,
                    type = "VMESS",
                    address = add,
                    port = port,
                    uuid = id,
                    alterId = aid,
                    security = scy,
                    network = net,
                    path = path,
                    host = host,
                    tls = tls,
                    sni = sni,
                    fingerprint = fingerprint,
                    alpn = alpn
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

                    val colonIndex = right.indexOf(':')
                    val address = if (colonIndex != -1) right.substring(0, colonIndex) else right
                    val port = if (colonIndex != -1) right.substring(colonIndex + 1).toIntOrNull() ?: 1080 else 1080

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

                val (address, port) = if (hostPort.contains(':')) {
                    val parts = hostPort.split(":", limit = 2)
                    parts[0] to (parts.getOrNull(1)?.toIntOrNull() ?: 443)
                } else {
                    hostPort to 443
                }

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

    /**
     * Natural identity of a server config (everything that matters to the
     * core connection). Two links that normalise to the same key describe the
     * same endpoint even if their display name differs.
     */
    private fun naturalKey(s: ServerEntity): String =
        listOf(
            s.type.lowercase(),
            s.address.trim().lowercase(),
            s.port.toString(),
            s.uuid.trim(),
            s.network.trim().lowercase(),
            s.sni.trim().lowercase(),
            s.flow.trim().lowercase(),
            s.publicKey.trim(),
            s.shortId.trim()
        ).joinToString("|")

    /**
     * Reuse the row id of an existing server with the same natural key so the
     * upsert updates it instead of inserting a duplicate. When multiple
     * incoming links share a key, only the first is kept.
     */
    private fun dedupeAgainstExisting(
        incoming: List<ServerEntity>,
        existing: List<ServerEntity>
    ): List<ServerEntity> {
        val keyToExistingId = existing.associate { naturalKey(it) to it.id }
        val seenIncoming = HashSet<String>()
        return incoming.mapNotNull { parsed ->
            val key = naturalKey(parsed)
            if (!seenIncoming.add(key)) return@mapNotNull null
            val existingId = keyToExistingId[key]
            if (existingId != null) parsed.copy(id = existingId) else parsed
        }
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
