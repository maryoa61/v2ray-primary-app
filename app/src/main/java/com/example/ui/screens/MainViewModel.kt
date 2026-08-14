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
<<<<<<< HEAD
import com.example.service.RuntimeSettings
import com.example.service.AutoConnectService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val database = V2RayDatabase.getDatabase(application)
    val repository = V2RayRepository(database).also { V2RayRepository.initializeSettings(application) }
    val vpnCoreManager = VpnCoreManager(application, repository)
    private val lifecycleMutex = Mutex()
=======
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val database = V2RayDatabase.getDatabase(application)
    val repository = V2RayRepository(database)
    val vpnCoreManager = VpnCoreManager(application, repository)
>>>>>>> 81099166748c1091a99f14777d37940c6ca17c63

    // Flow for VPN integration requests from MainActivity
    private val _vpnPermissionRequest = MutableSharedFlow<android.content.Intent>(extraBufferCapacity = 1)
    val vpnPermissionRequest = _vpnPermissionRequest.asSharedFlow()

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

    // Background optimizer service active status and launcher toggle (Disabled from root)
<<<<<<< HEAD
    val isAutoConnectActive: StateFlow<Boolean> = AutoConnectService.isServiceActive

    fun toggleAutoConnect() {
        viewModelScope.launch {
            val enabled = !isAutoConnectActive.value
            updateSettings(_runtimeSettings.value.copy(autoConnectEnabled = enabled))
            val context = getApplication<Application>()
            if (enabled) {
                val intent = android.content.Intent(context, AutoConnectService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
            } else {
                context.stopService(android.content.Intent(context, AutoConnectService::class.java))
            }
        }
    }

    // Preferences configuration (Saved locally inside DB or simple local memory)
    private val initialSettings = repository.getRuntimeSettings()
    private val _runtimeSettings = MutableStateFlow(initialSettings)
    val runtimeSettings: StateFlow<RuntimeSettings> = _runtimeSettings.asStateFlow()
    val routingMode: StateFlow<String> = runtimeSettings.map { it.routingMode }.stateIn(viewModelScope, SharingStarted.Eagerly, initialSettings.routingMode)
    val dnsServer: StateFlow<String> = runtimeSettings.map { it.dnsServers.firstOrNull().orEmpty() }.stateIn(viewModelScope, SharingStarted.Eagerly, initialSettings.dnsServers.firstOrNull().orEmpty())
    val bypassList: StateFlow<Boolean> = runtimeSettings.map { it.bypassList.isNotEmpty() }.stateIn(viewModelScope, SharingStarted.Eagerly, initialSettings.bypassList.isNotEmpty())
=======
    val isAutoConnectActive: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

    fun toggleAutoConnect() {
        // Disabled from root as requested
    }

    // Preferences configuration (Saved locally inside DB or simple local memory)
    private val _routingMode = MutableStateFlow("Bypass LAN & Mainland") // Bypass LAN & Mainland, Global, Direct
    val routingMode: StateFlow<String> = _routingMode.asStateFlow()

    private val _dnsServer = MutableStateFlow("1.1.1.1")
    val dnsServer: StateFlow<String> = _dnsServer.asStateFlow()

    private val _bypassList = MutableStateFlow(true)
    val bypassList: StateFlow<Boolean> = _bypassList.asStateFlow()
>>>>>>> 81099166748c1091a99f14777d37940c6ca17c63

    init {
        // هیچ سرور دمویی ساخته نمیشه
    }

    fun toggleVpn() {
        viewModelScope.launch {
            val isRunning = vpnState.value == VpnState.CONNECTED || vpnState.value == VpnState.CONNECTING
            if (isRunning) {
<<<<<<< HEAD
                vpnCoreManager.stopVpnAndAwait()
=======
                vpnCoreManager.stopVpn()
>>>>>>> 81099166748c1091a99f14777d37940c6ca17c63
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
<<<<<<< HEAD
            lifecycleMutex.withLock {
            // If connected, we should stop and reconnect to the new server
            val isRunning = vpnState.value == VpnState.CONNECTED || vpnState.value == VpnState.CONNECTING
            if (isRunning) {
                vpnCoreManager.switchServer(server)
            } else {
                repository.selectServer(server.id)
            }
            }
=======
            // If connected, we should stop and reconnect to the new server
            val isRunning = vpnState.value == VpnState.CONNECTED || vpnState.value == VpnState.CONNECTING
            if (isRunning) {
                vpnCoreManager.stopVpn()
                repository.selectServer(server.id)
                vpnCoreManager.toggleVpn(server)
            } else {
                repository.selectServer(server.id)
            }
>>>>>>> 81099166748c1091a99f14777d37940c6ca17c63
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

<<<<<<< HEAD
    fun setRoutingMode(mode: String) { updateSettings(_runtimeSettings.value.copy(routingMode = mode)); viewModelScope.launch { repository.log("ROUTING", "INFO", "Changed proxy routing strategy to: $mode") } }
    fun setDnsServer(dns: String) { updateSettings(_runtimeSettings.value.copy(dnsServers = dns.split(",").map { it.trim() }.filter { it.isNotEmpty() })); viewModelScope.launch { repository.log("SYSTEM", "INFO", "Updated DNS server address: $dns") } }
    fun setMtu(mtu: Int) { updateSettings(_runtimeSettings.value.copy(mtu = mtu.coerceIn(1280, 1500))) }
    fun setHttpInboundEnabled(enabled: Boolean) { updateSettings(_runtimeSettings.value.copy(httpInboundEnabled = enabled)) }
    fun setAutoConnectEnabled(enabled: Boolean) { updateSettings(_runtimeSettings.value.copy(autoConnectEnabled = enabled)) }
    private fun updateSettings(settings: RuntimeSettings) { _runtimeSettings.value = settings; repository.saveRuntimeSettings(settings) }
=======
    fun setRoutingMode(mode: String) {
        _routingMode.value = mode
        viewModelScope.launch {
            repository.log("ROUTING", "INFO", "Changed proxy routing strategy to: $mode")
        }
    }

    fun setDnsServer(dns: String) {
        _dnsServer.value = dns
        viewModelScope.launch {
            repository.log("SYSTEM", "INFO", "Updated DNS server address: $dns")
        }
    }
>>>>>>> 81099166748c1091a99f14777d37940c6ca17c63

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
