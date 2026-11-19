package com.example.data

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import com.example.service.RuntimeSettings
import com.example.service.XrayConfigGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder as JURLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

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
        @Volatile private var appContext: Context? = null
        private val realPingSemaphore = Semaphore(2)
        private val xrayAssetsLock = Any()

        fun initializeSettings(context: Context) {
            val applicationContext = context.applicationContext
            appContext = applicationContext
            preferences = applicationContext.getSharedPreferences("runtime_settings", Context.MODE_PRIVATE)
        }
    }
    fun getRuntimeSettings(): RuntimeSettings {
        val p = preferences ?: return RuntimeSettings()
        return RuntimeSettings(
            dnsServers = p.getString("dnsServers", "1.1.1.1,1.0.0.1")!!.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            routingMode = p.getString("routingMode", "Bypass LAN & Mainland")!!,
            mtu = p.getInt("mtu", 1400),
            httpInboundEnabled = p.getBoolean("httpInboundEnabled", false),
            bypassList = p.getString("bypassList", "")!!.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            autoConnectEnabled = p.getBoolean("autoConnectEnabled", false),
            fragmentEnabled = p.getBoolean("fragmentEnabled", false),
            fragmentPackets = p.getString("fragmentPackets", "tlshello") ?: "tlshello",
            fragmentLength = p.getString("fragmentLength", "50-100") ?: "50-100",
            fragmentInterval = p.getString("fragmentInterval", "10-20") ?: "10-20",
            fragmentMaxSplit = p.getString("fragmentMaxSplit", "10") ?: "10"
        )
    }
    fun saveRuntimeSettings(settings: RuntimeSettings) {
        preferences?.edit()
            ?.putString("dnsServers", settings.dnsServers.joinToString(","))
            ?.putString("routingMode", settings.routingMode)
            ?.putInt("mtu", settings.mtu)
            ?.putBoolean("httpInboundEnabled", settings.httpInboundEnabled)
            ?.putString("bypassList", settings.bypassList.joinToString(","))
            ?.putBoolean("autoConnectEnabled", settings.autoConnectEnabled)
            ?.putBoolean("fragmentEnabled", settings.fragmentEnabled)
            ?.putString("fragmentPackets", settings.fragmentPackets)
            ?.putString("fragmentLength", settings.fragmentLength)
            ?.putString("fragmentInterval", settings.fragmentInterval)
            ?.putString("fragmentMaxSplit", settings.fragmentMaxSplit)
            ?.apply()
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

    /**
     * Measures an actual request through the server's Xray configuration.
     * A raw TCP connect to address:port only checks whether a port is open; it
     * does not verify TLS/REALITY, credentials, transport, or proxy forwarding.
     */
    suspend fun testServerPing(serverId: Long): Int = realPingSemaphore.withPermit {
        withContext(Dispatchers.IO) {
            val server = serverDao.getServerById(serverId) ?: return@withContext -1
            val context = appContext
            if (context == null) {
                serverDao.updatePing(serverId, -2)
                log("PING", "ERROR", "Cannot test ${server.name}: application context is unavailable.")
                return@withContext -2
            }

            var process: Process? = null
            var configFile: File? = null
            try {
                prepareXrayAssets(context)
                val binary = File(context.applicationInfo.nativeLibraryDir, "libxray.so")
                if (!binary.exists() || binary.length() <= 1_000_000L) {
                    throw IOException("Xray executable is missing from the installed app.")
                }

                val socksPort = availableLocalPort()
                var httpPort = availableLocalPort()
                while (httpPort == socksPort) httpPort = availableLocalPort()
                val generatedConfigFile = File(context.cacheDir, "xray_ping_${server.id}_$socksPort.json")
                configFile = generatedConfigFile
                generatedConfigFile.writeText(
                    XrayConfigGenerator.generate(
                        server = server,
                        filesDir = context.filesDir,
                        socksPort = socksPort,
                        httpPort = httpPort,
                        runtimeSettings = getRuntimeSettings()
                    )
                )

                val processBuilder = ProcessBuilder(
                    binary.absolutePath,
                    "-config",
                    generatedConfigFile.absolutePath
                ).redirectErrorStream(true)
                processBuilder.environment()["XRAY_LOCATION_ASSET"] = context.filesDir.absolutePath
                processBuilder.environment()["V2RAY_LOCATION_ASSET"] = context.filesDir.absolutePath
                val runningProcess = processBuilder.start()
                process = runningProcess

                val lastCoreError = AtomicReference<String?>(null)
                Thread({
                    try {
                        runningProcess.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { line ->
                                if (line.contains("error", ignoreCase = true)) {
                                    lastCoreError.set(line.take(300))
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // The process may be intentionally terminated after the probe.
                    }
                }, "xray-ping-log-${server.id}").apply {
                    isDaemon = true
                    start()
                }

                val socksReady = runInterruptible(Dispatchers.IO) {
                    waitForSocks(runningProcess, socksPort, timeoutMs = 8_000L)
                }
                if (!socksReady) {
                    throw IOException(
                        lastCoreError.get() ?: "Xray SOCKS inbound did not start for this configuration."
                    )
                }

                val latencyMs = runInterruptible(Dispatchers.IO) {
                    measureHttpsThroughSocks(socksPort, timeoutMs = 8_000L)
                }
                serverDao.updatePing(serverId, latencyMs)
                log("PING", "SUCCESS", "Real proxy test for ${server.name}: ${latencyMs}ms")
                latencyMs
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                serverDao.updatePing(serverId, -2)
                log(
                    "PING",
                    "ERROR",
                    "Real proxy test failed for ${server.name} (${server.address}:${server.port}): " +
                            (e.localizedMessage ?: e.javaClass.simpleName)
                )
                -2
            } finally {
                process?.let { running ->
                    running.destroy()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && running.isAlive) {
                        running.destroyForcibly()
                    }
                }
                configFile?.delete()
            }
        }
    }

    private fun prepareXrayAssets(context: Context) {
        synchronized(xrayAssetsLock) {
            listOf("geoip.dat", "geosite.dat").forEach { name ->
                val destination = File(context.filesDir, name)
                if (!destination.exists() || destination.length() == 0L) {
                    try {
                        context.assets.open(name).use { input ->
                            destination.outputStream().use { output -> input.copyTo(output) }
                        }
                    } catch (_: Exception) {
                        // These assets are optional for the generated routing rules.
                    }
                }
            }
        }
    }

    private fun availableLocalPort(): Int = ServerSocket(0).use { it.localPort }

    private fun isProcessAlive(process: Process): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return process.isAlive
        return try {
            process.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }

    private fun waitForSocks(process: Process, port: Int, timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (!isProcessAlive(process)) return false
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 150) }
                return true
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedException("Xray ping probe was cancelled.").apply { initCause(e) }
                }
                try {
                    Thread.sleep(100)
                } catch (interrupted: InterruptedException) {
                    throw interrupted
                }
            }
        }
        return false
    }

    private fun measureHttpsThroughSocks(socksPort: Int, timeoutMs: Long): Int {
        val endpoints = listOf(
            "www.gstatic.com" to "/generate_204",
            "cp.cloudflare.com" to "/generate_204"
        )
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        val deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L
        fun operationTimeoutMs(): Int {
            val remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L
            if (remainingMs <= 0) throw IOException("HTTPS probe exceeded its ${timeoutMs}ms deadline.")
            return minOf(4_000L, remainingMs).toInt().coerceAtLeast(1)
        }

        var lastFailure: Exception? = null

        for ((host, path) in endpoints) {
            var rawSocket: Socket? = null
            var tlsSocket: SSLSocket? = null
            try {
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedException("Xray ping probe was cancelled.")
                }
                rawSocket = Socket(proxy)
                val startNanos = System.nanoTime()
                rawSocket.connect(InetSocketAddress.createUnresolved(host, 443), operationTimeoutMs())
                val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                tlsSocket = sslFactory
                    .createSocket(rawSocket, host, 443, true) as SSLSocket
                tlsSocket.soTimeout = operationTimeoutMs()
                tlsSocket.startHandshake()
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, tlsSocket.session)) {
                    throw IOException("TLS hostname verification failed for $host")
                }

                val request = "GET $path HTTP/1.1\r\n" +
                        "Host: $host\r\n" +
                        "User-Agent: V2RayDan-Config-Test\r\n" +
                        "Cache-Control: no-cache\r\n" +
                        "Connection: close\r\n\r\n"
                tlsSocket.outputStream.write(request.toByteArray(StandardCharsets.US_ASCII))
                tlsSocket.outputStream.flush()
                tlsSocket.soTimeout = operationTimeoutMs()
                val statusLine = BufferedReader(
                    InputStreamReader(tlsSocket.inputStream, StandardCharsets.US_ASCII)
                ).use { it.readLine() ?: throw IOException("HTTPS probe returned an empty response") }
                val responseCode = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
                    ?: throw IOException("Invalid HTTPS response: ${statusLine.take(120)}")
                val elapsedMs = ((System.nanoTime() - startNanos) / 1_000_000L)
                    .toInt()
                    .coerceAtLeast(1)
                if (responseCode in 200..399) return elapsedMs
                lastFailure = IOException("Probe returned HTTP $responseCode")
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedException("Xray ping probe was cancelled.").apply { initCause(e) }
                }
                lastFailure = e
            } finally {
                try {
                    tlsSocket?.close()
                } catch (_: Exception) {
                }
                try {
                    rawSocket?.close()
                } catch (_: Exception) {
                }
            }
        }

        throw IOException(
            "No HTTPS probe succeeded${lastFailure?.localizedMessage?.let { ": $it" }.orEmpty()}",
            lastFailure
        )
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
