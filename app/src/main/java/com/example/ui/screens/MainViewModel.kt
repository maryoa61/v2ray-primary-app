package com.example.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.LogEntity
import com.example.data.ServerEntity
import com.example.data.SubscriptionEntity
import com.example.data.V2RayDatabase
import com.example.data.V2RayRepository
import com.example.service.SpeedState
import com.example.service.VpnCoreManager
import com.example.service.VpnState
import com.example.service.RuntimeSettings
import com.example.service.AutoConnectService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val database = V2RayDatabase.getDatabase(application)
    val repository = V2RayRepository(database).also { V2RayRepository.initializeSettings(application) }
    val vpnCoreManager = VpnCoreManager(application, repository)

    // Flow for VPN integration requests from MainActivity
    private val _vpnPermissionRequest = MutableSharedFlow<android.content.Intent>(extraBufferCapacity = 1)
    val vpnPermissionRequest: SharedFlow<android.content.Intent> = _vpnPermissionRequest.asSharedFlow()

    // Data flows from DB
    val servers: StateFlow<List<ServerEntity>> = repository.allServers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeServer: StateFlow<ServerEntity?> = repository.activeServer
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val subscriptions: StateFlow<List<SubscriptionEntity>> = repository.allSubscriptions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val logs: StateFlow<List<LogEntity>> = repository.logs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // UI flows from simulated Core Service
    val vpnState: StateFlow<VpnState> = vpnCoreManager.vpnState
    val speedState: StateFlow<SpeedState> = vpnCoreManager.speedState
    val connectionDuration: StateFlow<Long> = vpnCoreManager.connectionDuration
    val connectedServer: StateFlow<ServerEntity?> = vpnCoreManager.connectedServer

    // Activity indicator states
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _isTestingPing = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
    val isTestingPing: StateFlow<Map<Long, Boolean>> = _isTestingPing.asStateFlow()

    val isAutoConnectActive: StateFlow<Boolean> = AutoConnectService.isServiceActive
    private val settings = MutableStateFlow(repository.getRuntimeSettings())
    val runtimeSettings: StateFlow<RuntimeSettings> = settings.asStateFlow()
    val routingMode: StateFlow<String> = settings.map { it.routingMode }.stateIn(viewModelScope, SharingStarted.Eagerly, settings.value.routingMode)
    val dnsServer: StateFlow<String> = settings.map { it.dnsServers.firstOrNull().orEmpty() }.stateIn(viewModelScope, SharingStarted.Eagerly, settings.value.dnsServers.firstOrNull().orEmpty())
    val bypassList: StateFlow<Boolean> = settings.map { it.bypassList.isNotEmpty() }.stateIn(viewModelScope, SharingStarted.Eagerly, settings.value.bypassList.isNotEmpty())
    private val lifecycleMutex = Mutex()

    fun toggleAutoConnect() {
        viewModelScope.launch {
            val enabled = !isAutoConnectActive.value
            settings.value = settings.value.copy(autoConnectEnabled = enabled)
            repository.saveRuntimeSettings(settings.value)
            val context = getApplication<Application>()
            val serviceIntent = android.content.Intent(context, AutoConnectService::class.java)
            if (enabled) context.startService(serviceIntent) else context.stopService(serviceIntent)
        }
    }

    init {
        // هیچ سرور دمویی ساخته نمیشه
    }

    fun toggleVpn() {
        viewModelScope.launch {
            val isRunning = vpnState.value == VpnState.CONNECTED || vpnState.value == VpnState.CONNECTING
            if (isRunning) {
                vpnCoreManager.stopVpn()
            } else {
                val currentActive = activeServer.value
                val context = getApplication<Application>()
                val vpnIntent = try {
                    android.net.VpnService.prepare(context)
                } catch (e: Exception) {
                    repository.log("VPN", "WARNING", "VpnService.prepare bypassed: ${e.localizedMessage}")
                    null
                }
                if (vpnIntent != null) {
                    _vpnPermissionRequest.tryEmit(vpnIntent)
                } else {
                    vpnCoreManager.toggleVpn(currentActive)
                }
            }
        }
    }

    fun toggleVpnAfterPermission() {
        viewModelScope.launch {
            val currentActive = activeServer.value
            vpnCoreManager.toggleVpn(currentActive)
        }
    }

    fun selectServer(server: ServerEntity) {
        viewModelScope.launch {
            // If connected, we should stop and reconnect to the new server
            val isRunning = vpnState.value == VpnState.CONNECTED || vpnState.value == VpnState.CONNECTING
            lifecycleMutex.withLock {
                if (isRunning) vpnCoreManager.switchServer(server) else repository.selectServer(server.id)
            }
        }
    }

    fun addManualServer(
        name: String,
        type: String,
        address: String,
        port: Int,
        uuid: String,
        network: String,
        path: String,
        tls: Boolean,
        sni: String,
        security: String = "none",
        flow: String = "",
        fingerprint: String = "",
        publicKey: String = "",
        shortId: String = ""
    ) {
        viewModelScope.launch {
            val newServer = ServerEntity(
                name = name.ifEmpty { "$type Server" },
                type = type.uppercase(),
                address = address.ifEmpty { "127.0.0.1" },
                port = port,
                uuid = uuid,
                network = network,
                path = path,
                tls = tls,
                sni = sni,
                security = security,
                flow = flow,
                fingerprint = fingerprint,
                publicKey = publicKey,
                shortId = shortId
            )
            repository.addServer(newServer)
        }
    }

    fun updateManualServer(
        id: Long,
        name: String,
        type: String,
        address: String,
        port: Int,
        uuid: String,
        network: String,
        path: String,
        tls: Boolean,
        sni: String,
        security: String = "none",
        flow: String = "",
        fingerprint: String = "",
        publicKey: String = "",
        shortId: String = ""
    ) {
        viewModelScope.launch {
            val updated = ServerEntity(
                id = id,
                name = name.ifEmpty { "$type Server" },
                type = type.uppercase(),
                address = address.ifEmpty { "127.0.0.1" },
                port = port,
                uuid = uuid,
                network = network,
                path = path,
                tls = tls,
                sni = sni,
                security = security,
                flow = flow,
                fingerprint = fingerprint,
                publicKey = publicKey,
                shortId = shortId
            )
            repository.updateServer(updated)
            repository.log("USER", "INFO", "Updated server profile: ${updated.name} (ID: $id)")
        }
    }

    fun deleteServer(server: ServerEntity) {
        viewModelScope.launch {
            repository.deleteServer(server)
            // If selected server was deleted, elect a new one
            val active = activeServer.value
            if (active?.id == server.id) {
                val remaining = servers.value.filter { it.id != server.id }
                if (remaining.isNotEmpty()) {
                    repository.selectServer(remaining.first().id)
                }
            }
        }
    }

    fun triggerPing(serverId: Long) {
        viewModelScope.launch {
            _isTestingPing.update { it + (serverId to true) }
            repository.testServerPing(serverId)
            _isTestingPing.update { it + (serverId to false) }
        }
    }

    fun pingAllServers() {
        viewModelScope.launch {
            val list = servers.value
            list.forEach { s ->
                triggerPing(s.id)
            }
        }
    }

    fun addSubscriptionUrl(name: String, url: String) {
        viewModelScope.launch {
            repository.addSubscription(name, url)
        }
    }

    fun deleteSubscription(sub: SubscriptionEntity) {
        viewModelScope.launch {
            repository.deleteSubscription(sub)
            viewModelScope.launch {
                repository.log("SUBSCRIPTION", "WARNING", "Removed subscription file: ${sub.name}")
            }
        }
    }

    fun syncAllSubscriptions() {
        viewModelScope.launch {
            _isSyncing.value = true
            val subs = subscriptions.value
            if (subs.isEmpty()) {
                repository.log("SUBSCRIPTION", "WARNING", "No subscriptions configured. Add a subscription first.")
            } else {
                subs.forEach { sub ->
                    repository.syncSubscription(sub)
                }
            }
            _isSyncing.value = false
        }
    }

    fun importFromClipboard(rawLink: String): Boolean {
        val parsed = repository.parseShareLink(rawLink.trim())
        return if (parsed != null) {
            viewModelScope.launch {
                repository.addServer(parsed)
                repository.log("USER", "SUCCESS", "Parsed and imported server link of type ${parsed.type}!")
            }
            true
        } else {
            false
        }
    }

    fun setRoutingMode(mode: String) { settings.value=settings.value.copy(routingMode=mode); repository.saveRuntimeSettings(settings.value) }
    fun setDnsServer(dns: String) { settings.value=settings.value.copy(dnsServers=dns.split(",").map { it.trim() }.filter { it.isNotEmpty() }); repository.saveRuntimeSettings(settings.value) }
    fun setMtu(mtu: Int) { settings.value=settings.value.copy(mtu=mtu.coerceIn(1280,1500)); repository.saveRuntimeSettings(settings.value) }
    fun setHttpInboundEnabled(enabled: Boolean) { settings.value=settings.value.copy(httpInboundEnabled=enabled); repository.saveRuntimeSettings(settings.value) }
    fun setAutoConnectEnabled(enabled: Boolean) { settings.value=settings.value.copy(autoConnectEnabled=enabled); repository.saveRuntimeSettings(settings.value) }
    fun clearLogHistory() {
        viewModelScope.launch {
            repository.clearLogs()
        }
    }

    override fun onCleared() {
        super.onCleared()
        vpnCoreManager.cleanUp()
    }
}
