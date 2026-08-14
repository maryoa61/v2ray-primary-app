package com.example.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
<<<<<<< HEAD
=======
import com.example.data.ServerEntity
>>>>>>> 81099166748c1091a99f14777d37940c6ca17c63
import com.example.data.V2RayDatabase
import com.example.data.V2RayRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

class AutoConnectService : Service() {
<<<<<<< HEAD
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private lateinit var repository: V2RayRepository
    private var pingLoopJob: Job? = null
    private var isRunning = false
    companion object { private const val LATENCY_SWITCH_THRESHOLD_MS = 80; private const val HEALTH_FAILURES_REQUIRED = 2; private val _isServiceActive = MutableStateFlow(false); val isServiceActive: StateFlow<Boolean> = _isServiceActive.asStateFlow() }
    override fun onCreate() { super.onCreate(); V2RayRepository.initializeSettings(applicationContext); repository = V2RayRepository(V2RayDatabase.getDatabase(applicationContext)); isRunning = true; _isServiceActive.value = true }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { if (!repository.getRuntimeSettings().autoConnectEnabled) { stopSelf(); return START_NOT_STICKY }; if (pingLoopJob == null) startPeriodicPingLoop(); return START_NOT_STICKY }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun startPeriodicPingLoop() { pingLoopJob = serviceScope.launch { var currentHealthFailures = 0; while (isRunning) { try { val servers = repository.allServers.first(); servers.forEach { repository.testServerPing(it.id) }; val updated = repository.allServers.first(); val current = repository.getSelectedServer(); val candidate = updated.filter { it.ping != null && it.ping > 0 }.minByOrNull { it.ping!! }; if (current != null && candidate != null && candidate.id != current.id) { val currentPing = current.ping ?: Int.MAX_VALUE; val currentHealthy = currentPing > 0; currentHealthFailures = if (currentHealthy) 0 else currentHealthFailures + 1; val meaningfullyBetter = currentPing - candidate.ping!! >= LATENCY_SWITCH_THRESHOLD_MS; if (!currentHealthy && currentHealthFailures >= HEALTH_FAILURES_REQUIRED || meaningfullyBetter && !currentHealthy) { VpnCoreManager.activeVpnCoreManager?.switchServer(candidate) ?: repository.selectServer(candidate.id) } } else { currentHealthFailures = 0 } } catch (e: Exception) { repository.log("AUTO-SELECTOR", "ERROR", "Background ping evaluation error: ${e.localizedMessage}") }; delay(20_000) } } }
    override fun onDestroy() { isRunning = false; pingLoopJob?.cancel(); serviceJob.cancel(); _isServiceActive.value = false; super.onDestroy() }
=======

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    
    private lateinit var database: V2RayDatabase
    private lateinit var repository: V2RayRepository
    
    private var isRunning = false
    private var pingLoopJob: Job? = null

    companion object {
        private val _isServiceActive = MutableStateFlow(false)
        val isServiceActive: StateFlow<Boolean> = _isServiceActive.asStateFlow()
    }

    override fun onCreate() {
        super.onCreate()
        database = V2RayDatabase.getDatabase(applicationContext)
        repository = V2RayRepository(database)
        _isServiceActive.value = true
        isRunning = true
        
        // Log service activation
        serviceScope.launch {
            repository.log("BACKGROUND-SERVICE", "SUCCESS", "Auto-Connect optimizer background daemon started.")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (pingLoopJob == null) {
            startPeriodicPingLoop()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun startPeriodicPingLoop() {
        pingLoopJob = serviceScope.launch {
            while (isRunning) {
                try {
                    repository.log("AUTO-SELECTOR", "INFO", "Initiating background round-robin ping survey across nodes...")
                    
                    val servers = repository.allServers.first()
                    if (servers.isNotEmpty()) {
                        // Ping all of them to update their latency values in the DB
                        servers.forEach { s ->
                            repository.testServerPing(s.id)
                        }
                        
                        // Refetch list to get updated latencies
                        val updatedServers = repository.allServers.first()
                        
                        // Find the one with lowest positive latency (ping > 0)
                        val reachableServers = updatedServers.filter { s -> s.ping != null && s.ping > 0 }
                        val lowestLatencyServer = reachableServers.minByOrNull { s -> s.ping ?: Int.MAX_VALUE }
                        
                        val currentActive = repository.getSelectedServer()
                        
                        if (lowestLatencyServer != null && lowestLatencyServer.id != currentActive?.id) {
                            repository.log(
                                "AUTO-SELECTOR", 
                                "SUCCESS", 
                                "Identified optimal lowest-latency node: ${lowestLatencyServer.name} (${lowestLatencyServer.ping}ms). Seamlessly switching traffic configuration..."
                            )
                            
                            // Select as active in db
                            repository.selectServer(lowestLatencyServer.id)
                            
                            // If VPN is active, trigger dynamic reconnection
                            val vpnManager = VpnCoreManager.activeVpnCoreManager
                            if (vpnManager != null && vpnManager.vpnState.value == VpnState.CONNECTED) {
                                repository.log("AUTO-SELECTOR", "INFO", "Active tunnel session detected. Dispatching connection handoff trigger.")
                                withContext(Dispatchers.Main) {
                                    vpnManager.stopVpn()
                                    delay(500) // allow teardown
                                    vpnManager.toggleVpn(lowestLatencyServer)
                                }
                            }
                        } else if (lowestLatencyServer != null) {
                            repository.log(
                                "AUTO-SELECTOR", 
                                "INFO", 
                                "Current path is optimal. Selected node: ${lowestLatencyServer.name} with lowest ping of ${lowestLatencyServer.ping}ms."
                            )
                        } else {
                            repository.log("AUTO-SELECTOR", "WARNING", "Ping survey complete: No reachable nodes found on this network route.")
                        }
                    } else {
                        repository.log("AUTO-SELECTOR", "WARNING", "Pinger bypassed: No configured VPN endpoints found.")
                    }
                } catch (e: Exception) {
                    repository.log("AUTO-SELECTOR", "ERROR", "Background ping evaluation error: ${e.localizedMessage}")
                }
                
                // Sleep for 20 seconds before next ping cycle (responsive demo rate)
                delay(20000)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        pingLoopJob?.cancel()
        serviceJob.cancel()
        _isServiceActive.value = false
        
        // Create an independent coroutine scope for logging the shutdown cleanly
        CoroutineScope(Dispatchers.IO).launch {
            repository.log("BACKGROUND-SERVICE", "WARNING", "Background daemon has shut down gracefully.")
        }
    }
>>>>>>> 81099166748c1091a99f14777d37940c6ca17c63
}
