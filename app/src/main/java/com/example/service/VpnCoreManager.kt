package com.example.service

import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.os.Build
import com.example.data.ServerEntity
import com.example.data.V2RayRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

enum class VpnState { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR }

data class SpeedState(
    val downloadSpeed: String = "0.0 B/s",
    val uploadSpeed: String = "0.0 B/s",
    val rawDownBytes: Long = 0,
    val rawUpBytes: Long = 0
)

class VpnCoreManager(private val context: Context, private val repository: V2RayRepository) {
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val lifecycleMutex = Mutex()
    private val _vpnState = MutableStateFlow(VpnState.DISCONNECTED)
    val vpnState: StateFlow<VpnState> = _vpnState.asStateFlow()
    private val _speedState = MutableStateFlow(SpeedState())
    val speedState: StateFlow<SpeedState> = _speedState.asStateFlow()
    private val _connectionDuration = MutableStateFlow(0L)
    val connectionDuration: StateFlow<Long> = _connectionDuration.asStateFlow()
    private val _connectedServer = MutableStateFlow<ServerEntity?>(null)
    val connectedServer: StateFlow<ServerEntity?> = _connectedServer.asStateFlow()
    private var trafficJob: Job? = null
    private var durationJob: Job? = null

    init { activeVpnCoreManager?.cleanUp(); activeVpnCoreManager = this }
    companion object { var activeVpnCoreManager: VpnCoreManager? = null }

    fun toggleVpn(server: ServerEntity?) {
        scope.launch {
            lifecycleMutex.withLock {
                if (_vpnState.value == VpnState.DISCONNECTED || _vpnState.value == VpnState.ERROR) {
                    if (server == null) { _vpnState.value = VpnState.ERROR; repository.log("VPN", "ERROR", "Cannot start VPN: No server selected."); return@withLock }
                    startVpnLocked(server)
                } else if (_vpnState.value == VpnState.CONNECTED || _vpnState.value == VpnState.CONNECTING) {
                    if (server == null || server.id == _connectedServer.value?.id) stopVpnLocked() else switchServerLocked(server)
                }
            }
        }
    }

    suspend fun switchServer(server: ServerEntity) = lifecycleMutex.withLock { switchServerLocked(server) }

    private suspend fun switchServerLocked(server: ServerEntity) {
        if (_vpnState.value != VpnState.DISCONNECTED) stopVpnLocked()
        repository.selectServer(server.id)
        startVpnLocked(server)
    }

    private suspend fun startVpnLocked(server: ServerEntity) {
        _vpnState.value = VpnState.CONNECTING
        _connectedServer.value = server
        _connectionDuration.value = 0L
        try {
            val intent = Intent(context, V2RayVpnService::class.java).setAction(V2RayVpnService.ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        } catch (e: Exception) {
            repository.log("VPN-SERVICE", "ERROR", "Failed to start real VPN service: ${e.localizedMessage}")
            _vpnState.value = VpnState.ERROR
        }
    }

    suspend fun stopVpnAndAwait() = lifecycleMutex.withLock { stopVpnLocked() }
    fun stopVpn() { scope.launch { stopVpnAndAwait() } }

    private suspend fun stopVpnLocked() {
        if (_vpnState.value == VpnState.DISCONNECTED) return
        _vpnState.value = VpnState.DISCONNECTING
        stopTracking()
        val intent = Intent(context, V2RayVpnService::class.java).setAction(V2RayVpnService.ACTION_STOP)
        try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent) }
        catch (e: Exception) { repository.log("VPN-SERVICE", "ERROR", "Failed to issue shutdown command: ${e.localizedMessage}") }
        withTimeoutOrNull(15_000) { vpnState.first { it == VpnState.DISCONNECTED || it == VpnState.ERROR } }
    }

    fun updateState(state: VpnState) { _vpnState.value = state }
    fun setConnectedServer(server: ServerEntity?) { _connectedServer.value = server }

    fun startTracking() {
        stopTracking()
        durationJob = scope.launch { while (isActive) { delay(1000); _connectionDuration.value += 1 } }
        trafficJob = scope.launch {
            val uid = context.applicationInfo.uid
            var lastRx = TrafficStats.getUidRxBytes(uid)
            var lastTx = TrafficStats.getUidTxBytes(uid)
            val unavailable = lastRx == TrafficStats.UNSUPPORTED.toLong() || lastTx == TrafficStats.UNSUPPORTED.toLong()
            val baseRx = lastRx; val baseTx = lastTx
            val downWindow = ArrayDeque<Long>(); val upWindow = ArrayDeque<Long>()
            while (isActive) {
                delay(1000)
                val rx = TrafficStats.getUidRxBytes(uid); val tx = TrafficStats.getUidTxBytes(uid)
                if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) {
                    _speedState.value = SpeedState("UNAVAILABLE", "UNAVAILABLE")
                    continue
                }
                val down = (rx - lastRx).coerceAtLeast(0); val up = (tx - lastTx).coerceAtLeast(0)
                lastRx = rx; lastTx = tx
                downWindow.addLast(down); upWindow.addLast(up)
                if (downWindow.size > 5) downWindow.removeFirst(); if (upWindow.size > 5) upWindow.removeFirst()
                _speedState.value = SpeedState(formatSpeed(downWindow.average().toLong()), formatSpeed(upWindow.average().toLong()), (rx-baseRx).coerceAtLeast(0), (tx-baseTx).coerceAtLeast(0))
            }
        }
    }
    fun stopTracking() { trafficJob?.cancel(); trafficJob = null; durationJob?.cancel(); durationJob = null; _connectionDuration.value = 0L; _speedState.value = SpeedState() }
    private fun formatSpeed(bytesPerSec: Long) = when { bytesPerSec >= 1_000_000 -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / 1_000_000.0); bytesPerSec >= 1_000 -> String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1_000.0); else -> "$bytesPerSec B/s" }
    fun cleanUp() { stopTracking(); scope.cancel() }
}
