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

    private var interfaceDescriptor: ParcelFileDescriptor? = null
    private var xrayProcess: Process? = null
    private var hevTunnelThread: Thread? = null
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private val cleanupMutex = Mutex()
    private val cleanupDone = AtomicBoolean(false)
    
    // برای جلوگیری از Reconnect بی‌نهایت در صورت قطعی پشت سر هم
    private var isManualStop = false

    companion object {
        const val ACTION_START = "com.example.service.START"
        const val ACTION_STOP = "com.example.service.STOP"
        private const val CHANNEL_ID = "v2ray_vpn_service_channel"
        private const val NOTIFICATION_ID = 1002
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_START) {
            isManualStop = false
            startVpn()
        } else if (action == ACTION_STOP) {
            isManualStop = true
            stopVpn()
        }
        return START_NOT_STICKY
    }

    private fun startVpn() {
        cleanupDone.set(false)

        createNotificationChannel()
        val intent = Intent(this, MainActivity::class.java)

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getActivity(this, 0, intent, pendingIntentFlags)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("V2Ray Dan Core Active")
            .setContentText("Connected in real tunnel mode. Routing all apps traffic through gateway.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        serviceScope.launch {
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
                repository.log("TUNNEL", "INFO", "Allocating local tun0 interface file descriptor...")

                // تغییر ۱: MTU روی 1400 تنظیم شد تا از Fragmentation جلوگیری شود
                val builder = Builder()
                    .setSession("V2RayDan")
                    .addAddress("172.19.0.1", 30) 
                    .addRoute("0.0.0.0", 0)       
                    .addDnsServer("8.8.8.8")      
                    .addDnsServer("8.8.8.8")      
                    .setMtu(1400) // بهینه‌سازی برای شبکه‌های موبایل و اورهد تونل

                try {
                    builder.addDisallowedApplication(packageName)
                    repository.log("TUNNEL", "INFO", "Bypassed package: $packageName")
                } catch (e: Exception) {
                    repository.log("TUNNEL", "WARNING", "Exclusion failed: ${e.localizedMessage}")
                }

                interfaceDescriptor = builder.establish()
                if (interfaceDescriptor != null) {
                    repository.log("TUNNEL", "SUCCESS", "Tun interface established.")
                } else {
                    repository.log("TUNNEL", "ERROR", "VpnService.Builder returned null Interface.")
                }
            } catch (e: Exception) {
                repository.log("TUNNEL", "ERROR", "Tunnel build failed: ${e.localizedMessage}")
                withContext(Dispatchers.Main) {
                    VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.ERROR)
                }
                stopSelf()
                return@launch
            }

            val fdNum = interfaceDescriptor?.fd ?: -1

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

            try {
                val commandList = mutableListOf(binary.absolutePath, "-config", configFile.absolutePath)

                val processBuilder = ProcessBuilder()
                    .command(commandList)
                    .redirectErrorStream(true)

                processBuilder.environment()["XRAY_LOCATION_ASSET"] = filesDir.absolutePath
                processBuilder.environment()["V2RAY_LOCATION_ASSET"] = filesDir.absolutePath

                xrayProcess = processBuilder.start()

                withContext(Dispatchers.Main) {
                    VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.CONNECTED)
                    VpnCoreManager.activeVpnCoreManager?.startTracking()
                }

                val logJob = serviceScope.launch {
                    val reader = BufferedReader(InputStreamReader(xrayProcess?.inputStream))
                    var line: String?
                    while (isActive && xrayProcess != null) {
                        line = withContext(Dispatchers.IO) { reader.readLine() }
                        if (line == null) break
                        if (line.isNotBlank()) {
                            val isNoisy = line.contains("tcp:") || line.contains("udp:") || line.contains("email:") || line.contains("accepted") || line.contains("127.0.0.1:")
                            if (!isNoisy || line.contains("warning", ignoreCase = true) || line.contains("error", ignoreCase = true)) {
                                val trimmedLine = if (line.length > 200) line.take(200) + "..." else line
                                repository.log("XRAY-CORE", if (line.contains("error", ignoreCase = true)) "ERROR" else "INFO", trimmedLine)
                            }
                        }
                    }
                }

                val socksReady = waitForSocksReady(XrayConfigGenerator.SOCKS_INBOUND_PORT)
                if (!socksReady) {
                    repository.log("XRAY-CORE", "ERROR", "SOCKS5 port handshake timeout.")
                    withContext(Dispatchers.Main) {
                        VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.ERROR)
                    }
                    logJob.cancel()
                    performCleanup(repository)
                    stopSelf()
                    return@launch
                }

                if (fdNum != -1) {
                    val hevConfigFile = File(cacheDir, "hev_tunnel.yml")
                    HevSocks5Tunnel.writeConfig(hevConfigFile, XrayConfigGenerator.SOCKS_INBOUND_PORT)

                    hevTunnelThread = Thread({
                        val exitCode = HevSocks5Tunnel.start(hevConfigFile.absolutePath, fdNum)
                        serviceScope.launch {
                            repository.log("HEV-TUNNEL", if (exitCode == 0) "INFO" else "ERROR", "hev loop status: $exitCode.")
                        }
                    }, "hev-socks5-tunnel").apply {
                        isDaemon = true
                        start()
                    }
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
                    
                    // تغییر ۲: منطق Reconnect در صورت قطعی غیرارادی
                    if (!isManualStop) {
                        repository.log("VPN", "WARNING", "Connection lost. Attempting to reconnect in 3 seconds...")
                        withContext(Dispatchers.Main) {
                            VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.CONNECTING) // وضعیت در حال اتصال مجدد
                        }
                        delay(3000) // صبر ۳ ثانیه ای قبل از اتصال مجدد
                        if (!isManualStop) startVpn() // فراخوانی مجدد
                        return@launch
                    }

                    withContext(Dispatchers.Main) {
                        VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.ERROR)
                        VpnCoreManager.activeVpnCoreManager?.setConnectedServer(null)
                        VpnCoreManager.activeVpnCoreManager?.stopTracking()
                    }
                }
            } catch (e: Throwable) {
                repository.log("XRAY-CORE", "ERROR", "Exception execution: ${e.localizedMessage ?: e.toString()}")
                performCleanup(repository)
                withContext(Dispatchers.Main) {
                    VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.ERROR)
                }
            }
        }
    }

    private suspend fun waitForSocksReady(port: Int, timeoutMs: Long = 5000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
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

    private suspend fun performCleanup(repository: V2RayRepository? = null) {
        cleanupMutex.withLock {
            if (cleanupDone.getAndSet(true)) {
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
        isManualStop = true
        serviceScope.launch {
            val db = V2RayDatabase.getDatabase(applicationContext)
            val repository = V2RayRepository(db)

            performCleanup(repository)

            withContext(Dispatchers.Main) {
                VpnCoreManager.activeVpnCoreManager?.updateState(VpnState.DISCONNECTED)
                VpnCoreManager.activeVpnCoreManager?.setConnectedServer(null)
                VpnCoreManager.activeVpnCoreManager?.stopTracking()
            }
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

    // تغییر ۳: حذف runBlocking خطرناک برای جلوگیری از ANR
    override fun onDestroy() {
        serviceScope.launch {
            performCleanup()
        }
        serviceJob.cancel() // لغو تمام کوروتین‌ها
        super.onDestroy()
    }
}
