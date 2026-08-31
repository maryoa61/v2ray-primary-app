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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

enum class VpnState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    ERROR
}

data class SpeedState(
    val downloadSpeed: String = "0.0 B/s",
    val uploadSpeed: String = "0.0 B/s",
    val rawDownBytes: Long = 0,
    val rawUpBytes: Long = 0
)

class VpnCoreManager(private val context: Context, private val repository: V2RayRepository) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val lifecycleMutex = Mutex()

    init {
        // جلوگیری از ایجاد چندین نمونه همزمان که باعث کرش می‌شود
        activeVpnCoreManager?.cleanUp()
        activeVpnCoreManager = this
    }

    companion object {
        var activeVpnCoreManager: VpnCoreManager? = null
    }
    
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

    private var pendingServer: ServerEntity? = null
    private var disconnectingServer: ServerEntity? = null

    init {
        scope.launch {
            vpnState.collect { state ->
                if ((state == VpnState.DISCONNECTED || state == VpnState.ERROR)) {
                    val next = pendingServer
                    pendingServer = null
                    if (next != null) {
                        startVpn(next)
                    }
                }
            }
        }
    }

    fun toggleVpn(server: ServerEntity?) {
        val state = _vpnState.value
        when {
            state == VpnState.DISCONNECTED || state == VpnState.ERROR -> {
                if (server == null) {
                    scope.launch { repository.log("VPN", "ERROR", "Cannot start VPN: No server selected.") }
                    _vpnState.value = VpnState.ERROR
                    return
                }
                startVpn(server)
            }
            state == VpnState.CONNECTED || state == VpnState.CONNECTING -> {
                if (server != null && server.id == _connectedServer.value?.id) {
                    stopVpn()
                } else if (server != null) {
                    pendingServer = server
                    stopVpnInternal(clearPending = false)
                } else {
                    stopVpn()
                }
            }
            state == VpnState.DISCONNECTING -> {
                if (server != null && server.id != disconnectingServer?.id) {
                    pendingServer = server
                } else {
                    pendingServer = null
                }
            }
        }
    }

    private fun startVpn(server: ServerEntity) {
        _vpnState.value = VpnState.CONNECTING
        _connectedServer.value = server
        disconnectingServer = null
        _connectionDuration.value = 0L

        try {
            val intent = Intent(context, V2RayVpnService::class.java).apply {
                action = V2RayVpnService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            scope.launch { repository.log("VPN-SERVICE", "ERROR", "Failed to start real VPN service: ${e.localizedMessage}") }
            _vpnState.value = VpnState.ERROR
        }
    }

    fun stopVpn() {
        stopVpnInternal(clearPending = true)
    }

    suspend fun switchServer(server: ServerEntity) {
        lifecycleMutex.withLock {
            val current = _connectedServer.value
            if (current?.id == server.id && (_vpnState.value == VpnState.CONNECTED || _vpnState.value == VpnState.CONNECTING)) return
            if (_vpnState.value != VpnState.DISCONNECTED && _vpnState.value != VpnState.ERROR) {
                stopVpnInternal(clearPending = true)
                // FIX (potential infinite hang): the service process can be
                // killed by the system (low memory / OEM battery saver) BEFORE
                // it processes ACTION_STOP, in which case DISCONNECTED never
                // arrives and this `first()` suspended forever — holding
                // lifecycleMutex, so every later connect/switch call queued up
                // behind it and the UI looked frozen. Bound the wait; if the
                // teardown confirmation does not arrive we continue to start
                // the new session anyway (the old process is dead).
                val settled = withTimeoutOrNull(15_000L) {
                    vpnState.filter { it == VpnState.DISCONNECTED || it == VpnState.ERROR }.first()
                    true
                }
                if (settled == null) {
                    scope.launch {
                        repository.log("VPN", "WARNING", "Previous session did not confirm teardown in time; reconnecting anyway.")
                    }
                }
            }
            repository.selectServer(server.id)
            startVpn(server)
        }
    }

    private fun stopVpnInternal(clearPending: Boolean) {
        if (_vpnState.value == VpnState.DISCONNECTED) return
        disconnectingServer = _connectedServer.value
        _vpnState.value = VpnState.DISCONNECTING
        if (clearPending) {
            pendingServer = null
        }
        stopTracking()

        try {
            val intent = Intent(context, V2RayVpnService::class.java).apply {
                action = V2RayVpnService.ACTION_STOP
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            scope.launch { repository.log("VPN-SERVICE", "ERROR", "Failed to issue shutdown command: ${e.localizedMessage}") }
        }
    }

    fun updateState(state: VpnState) {
        _vpnState.value = state
    }

    fun setConnectedServer(server: ServerEntity?) {
        _connectedServer.value = server
    }

    fun startTracking() {
        stopTracking()

        durationJob = scope.launch {
            while (isActive) {
                delay(1000)
                _connectionDuration.value += 1
            }
        }

        trafficJob = scope.launch {
            val uid = context.applicationInfo.uid
            var lastRxBytes = TrafficStats.getUidRxBytes(uid)
            var lastTxBytes = TrafficStats.getUidTxBytes(uid)
            if (lastRxBytes == TrafficStats.UNSUPPORTED.toLong()) lastRxBytes = 0
            if (lastTxBytes == TrafficStats.UNSUPPORTED.toLong()) lastTxBytes = 0

            val baseRx = lastRxBytes
            val baseTx = lastTxBytes

            while (isActive) {
                delay(1000)
                var currentRxBytes = TrafficStats.getUidRxBytes(uid)
                var currentTxBytes = TrafficStats.getUidTxBytes(uid)
                if (currentRxBytes == TrafficStats.UNSUPPORTED.toLong()) currentRxBytes = 0
                if (currentTxBytes == TrafficStats.UNSUPPORTED.toLong()) currentTxBytes = 0

                val downloadDelta = if (currentRxBytes >= lastRxBytes) currentRxBytes - lastRxBytes else 0L
                val uploadDelta = if (currentTxBytes >= lastTxBytes) currentTxBytes - lastTxBytes else 0L

                lastRxBytes = currentRxBytes
                lastTxBytes = currentTxBytes

                _speedState.value = SpeedState(
                    downloadSpeed = formatSpeed(downloadDelta),
                    uploadSpeed = formatSpeed(uploadDelta),
                    rawDownBytes = if (currentRxBytes >= baseRx) currentRxBytes - baseRx else 0L,
                    rawUpBytes = if (currentTxBytes >= baseTx) currentTxBytes - baseTx else 0L
                )
            }
        }
    }

    fun stopTracking() {
        trafficJob?.cancel()
        trafficJob = null
        durationJob?.cancel()
        durationJob = null
        _connectionDuration.value = 0L
        _speedState.value = SpeedState()
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1_000_000 -> String.format(Locale.US, "%.1f MB/s", bytesPerSec.toDouble() / 1_000_000.0)
            bytesPerSec >= 1_000 -> String.format(Locale.US, "%.1f KB/s", bytesPerSec.toDouble() / 1_000.0)
            else -> "$bytesPerSec B/s"
        }
    }

    fun cleanUp() {
        stopTracking()
        scope.cancel()
    }
}
