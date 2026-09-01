package com.example.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.example.data.V2RayDatabase
import com.example.data.V2RayRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Background auto-connector.
 *
 * Periodically pings all configured nodes and, when the CURRENTLY connected
 * node becomes unreachable for several rounds in a row, switches to the
 * fastest healthy node.
 *
 * Stability fixes in this revision:
 *  - Pings run CONCURRENTLY (bounded) instead of serially. The old loop ran
 *    one TCP connect (up to 1200 ms timeout) per server on a single
 *    coroutine; with 30+ duplicate subscription entries each round took tens
 *    of seconds, and the constant churn kept the radio active (battery/data).
 *  - The interval is 30 s (was 20 s) and pings only start while the VPN is
 *    actually connected — pinging from a disconnected state is useless and
 *    still woke the radio.
 *  - Switch decisions require the connected node to be dead for 2 rounds AND
 *    a healthy candidate that is meaningfully faster, so transient packet
 *    loss no longer triggers an unnecessary tunnel restart (a restart itself
 *    causes the "tunnel dropped" symptom users reported).
 */
class AutoConnectService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private lateinit var repository: V2RayRepository
    private var pingLoopJob: Job? = null
    private var isRunning = false

    companion object {
        private const val PING_INTERVAL_MS = 30_000L
        private const val MAX_CONCURRENT_PINGS = 6
        private const val LATENCY_SWITCH_THRESHOLD_MS = 80
        private const val HEALTH_FAILURES_REQUIRED = 2
        private val _isServiceActive = MutableStateFlow(false)
        val isServiceActive: StateFlow<Boolean> = _isServiceActive.asStateFlow()
    }

    override fun onCreate() {
        super.onCreate()
        V2RayRepository.initializeSettings(applicationContext)
        repository = V2RayRepository(V2RayDatabase.getDatabase(applicationContext))
        isRunning = true
        _isServiceActive.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!repository.getRuntimeSettings().autoConnectEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (pingLoopJob == null) startPeriodicPingLoop()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startPeriodicPingLoop() {
        pingLoopJob = serviceScope.launch {
            var failures = 0
            val pingSemaphore = Semaphore(MAX_CONCURRENT_PINGS)

            while (isActive && isRunning) {
                try {
                    // Only evaluate while a tunnel is up. When disconnected
                    // there is nothing to switch away from, and pinging on the
                    // raw link just burns battery.
                    val core = VpnCoreManager.activeVpnCoreManager
                    val connected = core?.let {
                        it.vpnState.value == VpnState.CONNECTED ||
                                it.vpnState.value == VpnState.CONNECTING
                    } ?: false

                    if (connected) {
                        val servers = repository.allServers.first()

                        // Concurrent bounded pings. testServerPing() is already
                        // on Dispatchers.IO; the semaphore caps socket churn.
                        coroutineScope {
                            servers.forEach { server ->
                                launch {
                                    pingSemaphore.withPermit {
                                        repository.testServerPing(server.id)
                                    }
                                }
                            }
                        }

                        val updated = repository.allServers.first()
                        val current = repository.getSelectedServer()
                        val healthy = updated.filter { (it.ping ?: -1) > 0 }
                        val candidate = healthy.minByOrNull { it.ping!! }

                        if (current != null && candidate != null && candidate.id != current.id) {
                            // The freshly measured ping of the CURRENT node:
                            // -1 / -2 / null means unreachable or not yet measured.
                            val currentPing = updated.firstOrNull { it.id == current.id }?.ping
                            val currentHealthy = currentPing != null && currentPing > 0

                            failures = if (currentHealthy) 0 else failures + 1

                            val gap = (currentPing ?: Int.MAX_VALUE) - (candidate.ping ?: Int.MAX_VALUE)

                            if (!currentHealthy &&
                                failures >= HEALTH_FAILURES_REQUIRED &&
                                gap >= LATENCY_SWITCH_THRESHOLD_MS
                            ) {
                                repository.log(
                                    "AUTO-SELECTOR",
                                    "WARNING",
                                    "Current node unreachable for $failures rounds; switching to faster node " +
                                            "'${candidate.name}' (${candidate.ping}ms)."
                                )
                                val manager = VpnCoreManager.activeVpnCoreManager
                                if (manager != null) {
                                    manager.switchServer(candidate)
                                } else {
                                    repository.selectServer(candidate.id)
                                }
                                failures = 0
                            }
                        } else {
                            failures = 0
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    repository.log("AUTO-SELECTOR", "ERROR", "Background ping evaluation error: ${e.localizedMessage}")
                }
                delay(PING_INTERVAL_MS)
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        pingLoopJob?.cancel()
        serviceJob.cancel()
        _isServiceActive.value = false
        super.onDestroy()
    }
}
