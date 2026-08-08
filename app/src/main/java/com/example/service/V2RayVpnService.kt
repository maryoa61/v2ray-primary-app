package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.V2RayDatabase
import com.example.data.V2RayRepository
import com.example.data.ServerEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class V2RayVpnService : VpnService() {

    // FIX: these fields are read/written from several threads (the session
    // coroutine on Dispatchers.IO, stopVpn()'s coroutine on IO, and
    // onDestroy() on the main thread). Without @Volatile there is no
    // happens-before edge between writer and reader, so a reader could see a
    // stale null and skip tearing down a live process/fd (resource leak), or
    // tear down a resource that a newer session already replaced.
    @Volatile
    private var interfaceDescriptor: ParcelFileDescriptor? = null

    @Volatile
    private var xrayProcess: Process? = null

    @Volatile
    private var hevTunnelThread: Thread? = null

    // True when a stop was requested by the user/system (stopVpn / onDestroy).
    // Used to tell an "intentional teardown" apart from a spontaneous crash of
    // the native tunnel so we don't report spurious ERRORs on clean shutdown.
    @Volatile
    private var intentionalStop = false

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    // Handle of the running session's coroutine. Cancelling it is the
    // "intentional stop" signal: after xrayProcess.waitFor() returns, the
    // session coroutine checks coroutineContext.isActive and only reports an
    // ERROR when it is still active (real crash). Without this, every clean
    // disconnect raced with the process-exit block and ended in ERROR.
    // sessionJob is only touched from the main thread (onStartCommand).
    private var sessionJob: Job? = null

    // --- Cleanup synchronization -------------------------------------------------
    // The old bug: stopVpn() (triggered by ACTION_STOP / onDestroy) and the
    // post-waitFor() cleanup block inside startVpn() (triggered when the xray
    // process dies/gets destroyed) could BOTH run their teardown logic at the
    // same time, on two different coroutines. Both paths called
    // HevSocks5Tunnel.stop() and touched xrayProcess/interfaceDescriptor
    // concurrently -> double-free / use-after-close in the native JNI layer ->
    // SIGSEGV. cleanupMutex + cleanupDone make teardown idempotent and
    // serialized: whichever caller gets there first does the real cleanup;
    // every other caller just waits for it to finish and then no-ops.
    //
    // FIX: cleanupDone is now only reset inside the session coroutine while
    // holding cleanupMutex, so a brand-new session cannot arm the flag while
    // the previous session's teardown is still in flight (that race could
    // re-enable the double-stop of the native tunnel -> SIGSEGV).
    private val cleanupMutex = Mutex()
    private val cleanupDone = AtomicBoolean(false)

    companion object {
        const val ACTION_START = "com.example.service.START"
        const val ACTION_STOP = "com.example.service.STOP"
        private const val CHANNEL_ID = "v2ray_vpn_service_channel"
        private const val NOTIFICATION_ID = 1002
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_START) {
            startVpn()
        } else if (action == ACTION_STOP) {
            stopVpn()
        }
        return START_NOT_STICKY
    }

    private fun startVpn() {
        // NOTE: cleanupDone is NOT reset here anymore. It is reset inside the
        // session coroutine, under cleanupMutex, so a new session can never
        // start while the previous session's teardown is still in flight.

        createNotificationChannel()
        val intent = Intent(this, MainActivity::class.java)

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, pendingIntentFlags
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("V2Ray Dan Core Active")
            .setContentText("Connected in real tunnel mode. Routing all apps traffic through gateway.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        // Fresh session: any stop that comes later is against THIS session.
        intentionalStop = false

        sessionJob = serviceScope.launch {
            // If the previous session is still tearing down, wait for it to
            // finish BEFORE arming the cleanup flag for this new session.
            cleanupMutex.withLock { cleanupDone.set(false) }

            val db = V2RayDatabase.getDatabase(applicationContext)
            val repository = V2RayRepository(db)
            val server = repository.getSelectedServer()

            if (server == null) {
                repository.log("VPN", "ERROR", "Cannot start VPN: No active server selected.")
                withContext(Dispatchers.Main) {
                    VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.ERROR)
                }
                stopSelf()
                return@launch
            }

            repository.log("VPN", "INFO", "Connecting to node: ${server.name} (${server.address}:${server.port})")

            try {
                /*
                 * Startup order is intentional:
                 *
                 *   prepare assets/config -> start Xray -> wait for SOCKS
                 *   -> establish TUN -> start HEV immediately -> CONNECTED
                 *
                 * Builder.establish() installs the VPN route immediately. Doing
                 * it before Xray/HEV are ready creates a window where packets
                 * enter TUN but nothing consumes them, which causes the brief
                 * internet interruption seen during VPN startup.
                 */

                repository.log("TUNNEL", "INFO", "Preparing Xray before installing VPN routes...")

                try {
                    listOf("geoip.dat", "geosite.dat").forEach { filename ->
                        val destFile = File(filesDir, filename)
                        if (!destFile.exists() || destFile.length() == 0L) {
                            assets.open(filename).use { input ->
                                destFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    repository.log("SYSTEM", "WARNING", "Databases sync error: ${e.localizedMessage}")
                }

                val configJson = XrayConfigGenerator.generate(server, filesDir)
                val configFile = File(cacheDir, "xray_config.json")
                try {
                    configFile.writeText(configJson)
                } catch (e: Exception) {
                    repository.log("CONFIG", "ERROR", "Failed to cache configuration: ${e.localizedMessage}")
                }

                val binary = locateCoreBinary(applicationContext, repository)
                if (binary == null || !binary.exists()) {
                    repository.log("XRAY-CORE", "ERROR", "Binary execute target missing.")
                    withContext(Dispatchers.Main) {
                        VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.ERROR)
                    }
                    stopSelf()
                    return@launch
                }

                val commandList = mutableListOf<String>().apply {
                    add(binary.absolutePath)
                    add("-config")
                    add(configFile.absolutePath)
                }

                val processBuilder = ProcessBuilder()
                    .command(commandList)
                    .redirectErrorStream(true)

                processBuilder.environment()["XRAY_LOCATION_ASSET"] = filesDir.absolutePath
                processBuilder.environment()["V2RAY_LOCATION_ASSET"] = filesDir.absolutePath

                xrayProcess = processBuilder.start()
                repository.log("XRAY-CORE", "INFO", "Xray process started before VPN route installation.")

                val logJob = serviceScope.launch {
                    val process = xrayProcess ?: return@launch
                    val reader = BufferedReader(InputStreamReader(process.inputStream))

                    var lastNormalized = ""
                    var repeatCount = 0
                    var lastRepeatSummaryAt = 0L

                    while (isActive && xrayProcess === process) {
                        val line = try {
                            reader.readLine()
                        } catch (e: Exception) {
                            null
                        }

                        if (line == null) break

                        if (line.isNotBlank()) {
                            val isNoisy = line.contains("tcp:") ||
                                    line.contains("udp:") ||
                                    line.contains("email:") ||
                                    line.contains("accepted") ||
                                    line.contains("127.0.0.1:")

                            if (!isNoisy ||
                                line.contains("warning", ignoreCase = true) ||
                                line.contains("error", ignoreCase = true)
                            ) {
                                val normalized = line
                                    .replace(Regex("\\[[0-9]+\\]"), "[ID]")
                                    .replace(
                                        Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}:[0-9]+->"),
                                        "SRC->"
                                    )

                                if (normalized == lastNormalized) {
                                    repeatCount++
                                    val now = System.currentTimeMillis()
                                    if (repeatCount % 25 == 0 &&
                                        now - lastRepeatSummaryAt > 2000
                                    ) {
                                        repository.log(
                                            "XRAY-CORE",
                                            "WARNING",
                                            "… last error repeated $repeatCount times (suppressing flood)"
                                        )
                                        lastRepeatSummaryAt = now
                                    }
                                    continue
                                }

                                if (repeatCount > 0) {
                                    repository.log(
                                        "XRAY-CORE",
                                        "WARNING",
                                        "… previous message repeated $repeatCount times"
                                    )
                                    repeatCount = 0
                                }

                                val trimmedLine =
                                    if (line.length > 200) line.take(200) + "..." else line

                                repository.log(
                                    "XRAY-CORE",
                                    if (line.contains("error", ignoreCase = true)) "ERROR" else "INFO",
                                    trimmedLine
                                )

                                lastNormalized = normalized
                            }
                        }
                    }
                }

                val socksReady = waitForSocksReady(
                    XrayConfigGenerator.SOCKS_INBOUND_PORT,
                    timeoutMs = 10000,
                    processAlive = { xrayProcess?.isAlive ?: false }
                )

                if (!socksReady) {
                    repository.log(
                        "XRAY-CORE",
                        "ERROR",
                        "SOCKS5 inbound never came up in time (or core exited early)."
                    )
                    logJob.cancel()
                    performCleanup(repository)

                    withContext(Dispatchers.Main) {
                        VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.ERROR)
                    }

                    stopSelf()
                    return@launch
                }

                /*
                 * Xray is now ready. Install the full-device route only after
                 * the proxy endpoint exists, minimizing the route-without-core
                 * window.
                 */
                repository.log("TUNNEL", "INFO", "Xray SOCKS is ready; installing VPN interface...")

                val builder = Builder()
                    .setSession("V2RayDan")
                    .addAddress("172.19.0.1", 30)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8")
                    .setMtu(HevSocks5Tunnel.TUNNEL_MTU)

                try {
                    builder.addDisallowedApplication(packageName)
                    repository.log("TUNNEL", "INFO", "Bypassed package: $packageName")
                } catch (e: Exception) {
                    repository.log("TUNNEL", "WARNING", "Exclusion failed: ${e.localizedMessage}")
                }

                interfaceDescriptor = builder.establish()
                if (interfaceDescriptor == null) {
                    repository.log("TUNNEL", "ERROR", "VpnService.Builder returned null Interface.")
                    logJob.cancel()
                    performCleanup(repository)

                    withContext(Dispatchers.Main) {
                        VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.ERROR)
                    }

                    stopSelf()
                    return@launch
                }

                repository.log("TUNNEL", "SUCCESS", "Tun interface established.")

                val fdNum = interfaceDescriptor?.fd ?: -1
                if (fdNum == -1) {
                    repository.log("TUNNEL", "ERROR", "Invalid TUN file descriptor.")
                    logJob.cancel()
                    performCleanup(repository)

                    withContext(Dispatchers.Main) {
                        VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.ERROR)
                    }

                    stopSelf()
                    return@launch
                }

                /*
                 * Nothing expensive is done between establish() and HEV.
                 * The route is live now, so start the TUN consumer immediately.
                 */
                val hevConfigFile = File(cacheDir, "hev_tunnel.yml")
                HevSocks5Tunnel.writeConfig(
                    hevConfigFile,
                    XrayConfigGenerator.SOCKS_INBOUND_PORT
                )

                hevTunnelThread = Thread({
                    var exitCode = -1
                    try {
                        serviceScope.launch {
                            repository.log(
                                "HEV-TUNNEL",
                                "INFO",
                                "Starting tunnel: fd=$fdNum, config=${hevConfigFile.absolutePath}, " +
                                        "configExists=${hevConfigFile.exists()}, configSize=${hevConfigFile.length()}"
                            )
                        }

                        exitCode = HevSocks5Tunnel.start(
                            hevConfigFile.absolutePath,
                            fdNum
                        )

                        serviceScope.launch {
                            repository.log(
                                "HEV-TUNNEL",
                                if (exitCode == 0) "INFO" else "ERROR",
                                "hev loop exited with code: $exitCode"
                            )
                        }
                    } catch (e: Exception) {
                        serviceScope.launch {
                            repository.log(
                                "HEV-TUNNEL",
                                "ERROR",
                                "Tunnel exception: ${e.message}\n${e.stackTraceToString()}"
                            )
                        }
                    }

                    if (exitCode != 0 && !intentionalStop) {
                        serviceScope.launch {
                            repository.log(
                                "HEV-TUNNEL",
                                "ERROR",
                                "Tunnel aborted unexpectedly (code $exitCode). Initiating teardown."
                            )
                            VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.ERROR)
                            performCleanup(repository)
                            stopSelf()
                        }
                    }
                }, "hev-socks5-tunnel").apply {
                    isDaemon = true
                    start()
                }

                withContext(Dispatchers.Main) {
                    VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.CONNECTED)
                    VpnCoreManager.activeVpnCoreManager?.startTracking()
                }

                val exitCode = try {
                    xrayProcess?.waitFor()
                } catch (e: Exception) {
                    null
                }

                logJob.cancel()

                if (coroutineContext.isActive) {
                    repository.log("XRAY-CORE", "ERROR", "Core exited code: $exitCode.")
                    performCleanup(repository)

                    withContext(Dispatchers.Main) {
                        VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.ERROR)
                        VpnCoreManager.activeVpnCoreManager?.setConnectedServer(null)
                        VpnCoreManager.activeVpnCoreManager?.stopTracking()
                    }
                }
            } catch (e: Throwable) {
                // FIX: let intentional cancellation (sessionJob.cancel() from
                // stopVpn()) propagate silently — it is not an error.
                if (e is CancellationException) throw e
                repository.log("XRAY-CORE", "ERROR", "Exception execution: ${e.localizedMessage ?: e.toString()}")
                performCleanup(repository)
                withContext(Dispatchers.Main) {
                    VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.ERROR)
                }
            }
        }
    }

    private suspend fun waitForSocksReady(
        port: Int,
        timeoutMs: Long = 10000,
        processAlive: () -> Boolean = { true }
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            // If the xray process already died, don't burn the remaining timeout
            // polling a dead port — fail fast so the UI lands on ERROR quickly
            // instead of "CONNECTED then dropped" a second later.
            if (!processAlive()) return false
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 300)
                    return true
                }
            } catch (e: Exception) {
                delay(150)
            }
        }
        return false
    }

    private suspend fun locateCoreBinary(context: Context, repository: V2RayRepository): File? {
        return withContext(Dispatchers.IO) {
            val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
            val nativeBinary = File(nativeLibDir, "libxray.so")
            if (nativeBinary.exists() && nativeBinary.length() > 1_000_000) {
                repository.log("SYSTEM", "SUCCESS", "Located library path: ${nativeBinary.absolutePath}")
                nativeBinary
            } else {
                null
            }
        }
    }

    /**
     * The single, idempotent teardown path. Whether it's called from the
     * user hitting "disconnect" (stopVpn) or from the xray process dying on
     * its own (startVpn's post-waitFor block), it's safe to call this from
     * multiple coroutines concurrently: the mutex serializes callers, and
     * cleanupDone ensures only the first caller through actually touches the
     * native tunnel / process / fd. Everyone else just waits and returns.
     */
    private suspend fun performCleanup(repository: V2RayRepository? = null) {
        cleanupMutex.withLock {
            if (cleanupDone.getAndSet(true)) {
                // Someone else already ran (or is running) real cleanup while
                // we were waiting on the mutex — nothing left to do.
                return@withLock
            }

            try {
                HevSocks5Tunnel.stop()
                hevTunnelThread?.join(2000)
            } catch (e: Exception) {
                Log.e("TUNNEL", "Stop error: ${e.localizedMessage}")
                repository?.log("TUNNEL", "ERROR", "Stop error: ${e.localizedMessage}")
            } finally {
                hevTunnelThread = null
            }

            try {
                xrayProcess?.destroy()
            } catch (e: Exception) {
                Log.e("CORE", "Destroy error: ${e.localizedMessage}")
                repository?.log("CORE", "ERROR", "Destroy error: ${e.localizedMessage}")
            } finally {
                xrayProcess = null
            }

            try {
                interfaceDescriptor?.close()
            } catch (e: Exception) {
                Log.e("INTERFACE", "Close error: ${e.localizedMessage}")
                repository?.log("INTERFACE", "ERROR", "Close error: ${e.localizedMessage}")
            } finally {
                interfaceDescriptor = null
            }
        }
    }

    private fun stopVpn() {
        // Tell the running session this is an intentional stop: its
        // post-waitFor() block will see isActive == false and won't report an
        // ERROR / clobber the state we set below, and the native tunnel won't
        // treat a clean quit as a crash.
        intentionalStop = true
        sessionJob?.cancel()

        serviceScope.launch {
            val db = V2RayDatabase.getDatabase(applicationContext)
            val repository = V2RayRepository(db)

            performCleanup(repository)

            // State updates are thread-safe on VpnCoreManager — no need to hop
            // to the main thread for them.
            VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.DISCONNECTED)
            VpnCoreManager.activeVpnCoreManager?.setConnectedServer(null)
            VpnCoreManager.activeVpnCoreManager?.stopTracking()

            // Only the foreground notification must be touched on the main thread.
            withContext(Dispatchers.Main) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            }

            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "V2Ray Dan System Status Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        // Cancel the scope first so the session coroutine's post-waitFor()
        // block sees isActive == false and won't report an ERROR while the
        // service is being torn down.
        intentionalStop = true
        serviceJob.cancel()

        // Run cleanup on a separate thread so onDestroy() never blocks the
        // main thread. If a stopVpn()/performCleanup() call is already in
        // flight from ACTION_STOP, this one will just block on the mutex, see
        // cleanupDone == true, and return immediately — no double teardown.
        Thread {
            runBlocking {
                performCleanup()
            }
        }.start()

        super.onDestroy()
    }
}
