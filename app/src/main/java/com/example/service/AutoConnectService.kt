package com.example.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.example.data.V2RayDatabase
import com.example.data.V2RayRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

class AutoConnectService : Service() {
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
}
