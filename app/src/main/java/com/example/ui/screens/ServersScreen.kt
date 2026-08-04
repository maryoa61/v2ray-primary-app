package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import com.example.data.ServerEntity
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun ServersScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val servers by viewModel.servers.collectAsState()
    val activeServer by viewModel.activeServer.collectAsState()
    val isTestingPing by viewModel.isTestingPing.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(SlateDark),
        snackbarHost = { SnackbarHost(snackbarHostState) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = SlateCard,
                contentColor = SlateTextPrimary,
                actionColor = CyberCyan
            )
        }},
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = CyberGreen,
                contentColor = SlateDark,
                modifier = Modifier.testTag("add_server_fab")
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "Add Node Server")
            }
        },
        containerColor = SlateDark
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Action Header bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "NODE SELECTION",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = SlateTextSecondary,
                        letterSpacing = 1.5.sp
                    )
                    Text(
                        text = "Gate Servers",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = SlateTextPrimary
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Diagnostic Latency Test all nodes button
                    IconButton(
                        onClick = { viewModel.pingAllServers() },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = SlateSurface,
                            contentColor = CyberCyan
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = "Test Connection Latency",
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Bulk subscription Sync button
                    FilledTonalButton(
                        onClick = {
                            viewModel.syncAllSubscriptions()
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "Refreshing configuration subscriptions...",
                                    duration = SnackbarDuration.Short
                                )
                            }
                        },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = SlateSurface,
                            contentColor = CyberGreen
                        )
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = CyberGreen,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Sync,
                                    contentDescription = "Sync Node Data",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Sync Feed", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Server Content Layout
            if (servers.isEmpty()) {
                EmptyServerState(onAddClick = { showAddDialog = true })
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    items(servers, key = { it.id }) { server ->
                        ServerListItem(
                            server = server,
                            isSelected = activeServer?.id == server.id,
                            isTestingPing = isTestingPing[server.id] ?: false,
                            onSelect = { viewModel.selectServer(server) },
                            onPing = { viewModel.triggerPing(server.id) },
                            onDelete = { viewModel.deleteServer(server) }
                        )
                    }
                }
            }
        }

        if (showAddDialog) {
            AddServerDialog(
                viewModel = viewModel,
                onDismiss = { showAddDialog = false },
                onSuccess = { message ->
                    showAddDialog = false
                    scope.launch {
                        snackbarHostState.showSnackbar(message)
                    }
                }
            )
        }
    }
}

@Composable
fun ServerListItem(
    server: ServerEntity,
    isSelected: Boolean,
    isTestingPing: Boolean,
    onSelect: () -> Unit,
    onPing: () -> Unit,
    onDelete: () -> Unit
) {
    val outlineCol = if (isSelected) CyberGreen else SlateBorder
    val cardBg = if (isSelected) SlateSurface else SlateCard

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("server_item_${server.id}")
            .clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        shape = RoundedCornerShape(16.dp),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(outlineCol))
    ) {
        Row(
            modifier = Modifier
                .padding(vertical = 12.dp, horizontal = 14.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Server Node Type Tag Brand
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        when (server.type.uppercase()) {
                            "VLESS" -> CyberCyan.copy(alpha = 0.12f)
                            "VMESS" -> CyberGreen.copy(alpha = 0.12f)
                            "SHADOWSOCKS" -> CyberMagenta.copy(alpha = 0.12f)
                            else -> CyberGold.copy(alpha = 0.12f)
                        }
                    )
            ) {
                Text(
                    text = when (server.type.uppercase()) {
                        "SHADOWSOCKS" -> "SS"
                        else -> server.type.uppercase()
                    },
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = when (server.type.uppercase()) {
                        "VLESS" -> CyberCyan
                        "VMESS" -> CyberGreen
                        "SHADOWSOCKS" -> CyberMagenta
                        else -> CyberGold
                    }
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Address Details
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = server.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = SlateTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (server.tls) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "SSL Secure Encrypted",
                            tint = CyberCyan,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${server.address}:${server.port} • ${server.network.uppercase()}",
                    fontSize = 11.sp,
                    color = SlateTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Latency Speed Test and Actions
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Ping Display Card
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(SlateDark)
                        .clickable(onClick = onPing)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isTestingPing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            color = CyberGreen,
                            strokeWidth = 1.5.dp
                        )
                    } else {
                        val pingVal = server.ping
                        val (pingText, pingCol) = when {
                            pingVal == null -> "---" to SlateTextSecondary
                            pingVal == -2 -> "Timeout" to CyberPink
                            pingVal < 120 -> "${pingVal}ms" to CyberGreen
                            pingVal < 250 -> "${pingVal}ms" to CyberGold
                            else -> "${pingVal}ms" to CyberGold.copy(alpha = 0.7f)
                        }

                        Text(
                            text = pingText,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = pingCol
                        )
                    }
                }

                // Delete node server configuration
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Delete Node Configuration",
                        tint = SlateTextSecondary.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyServerState(onAddClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(72.dp)
                .background(SlateSurface, CircleShape)
                .border(1.dp, SlateBorder, CircleShape)
        ) {
            Icon(
                imageVector = Icons.Default.PortableWifiOff,
                contentDescription = "No configs",
                tint = CyberGold,
                modifier = Modifier.size(36.dp)
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = "No Connection Nodes",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = SlateTextPrimary
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Import a configuration subscription or add nodes manually to connect to V2Ray gateways.",
            fontSize = 12.sp,
            color = SlateTextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = onAddClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = CyberGreen,
                contentColor = SlateDark
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(imageVector = Icons.Default.Create, contentDescription = "Manual Setup")
            Spacer(modifier = Modifier.width(6.dp))
            Text("Create Gateway Profile", fontWeight = FontWeight.Bold)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddServerDialog(
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    onSuccess: (String) -> Unit
) {
    var activeTab by remember { mutableStateOf(0) } // 0: Import Share link, 1: Manual Input Setup

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .wrapContentHeight()
            .testTag("add_server_dialog"),
        content = {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = SlateSurface,
                border = BorderStroke(1.dp, SlateBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    // Header title block
                    Text(
                        text = "NEW NODE CLIENT",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = SlateTextSecondary,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "Add Connection Gateway",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = SlateTextPrimary,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    // Tab Row
                    TabRow(
                        selectedTabIndex = activeTab,
                        containerColor = SlateDark,
                        contentColor = CyberCyan,
                        indicator = { tabPositions ->
                            TabRowDefaults.SecondaryIndicator(
                                modifier = Modifier.tabIndicatorOffset(tabPositions[activeTab]),
                                color = CyberCyan
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                    ) {
                        Tab(
                            selected = activeTab == 0,
                            onClick = { activeTab = 0 },
                            text = { Text("Share Link", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        )
                        Tab(
                            selected = activeTab == 1,
                            onClick = { activeTab = 1 },
                            text = { Text("Form Editor", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (activeTab == 0) {
                        AddByShareLinkView(viewModel, onSuccess)
                    } else {
                        AddByManualFormView(viewModel, onSuccess)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            colors = ButtonDefaults.textButtonColors(contentColor = SlateTextSecondary)
                        ) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    )
}

@Composable
fun AddByShareLinkView(
    viewModel: MainViewModel,
    onSuccess: (String) -> Unit
) {
    var linkText by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Copy/paste a VMess, VLess, Trojan, or ShadowSocks subscription or share link from your secure network supplier.",
            fontSize = 12.sp,
            color = SlateTextSecondary,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        OutlinedTextField(
            value = linkText,
            onValueChange = { linkText = it },
            label = { Text("Connection Link", color = SlateTextSecondary) },
            placeholder = { Text("vless://7a6e12e1-419b-4ff2-a4e1...", color = SlateTextSecondary.copy(alpha = 0.5f)) },
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .testTag("share_link_input"),
            textStyle = LocalTextStyle.current.copy(
                color = SlateTextPrimary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            ),
            trailingIcon = {
                // Paste button helper
                IconButton(
                    onClick = {
                        val clip = clipboardManager.getText()
                        if (clip != null) {
                            linkText = clip.text
                        }
                    }
                ) {
                    Icon(imageVector = Icons.Default.ContentPaste, contentDescription = "Paste", tint = CyberCyan)
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = CyberCyan,
                unfocusedBorderColor = SlateBorder,
                focusedContainerColor = SlateDark,
                unfocusedContainerColor = SlateDark
            ),
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(14.dp))

        Button(
            onClick = {
                if (linkText.isNotBlank()) {
                    val ok = viewModel.importFromClipboard(linkText.trim())
                    if (ok) {
                        onSuccess("Profile parsed and loaded successfully!")
                    } else {
                        onSuccess("Invalid or unsupported protocol link.")
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("import_link_button"),
            colors = ButtonDefaults.buttonColors(
                containerColor = CyberCyan,
                contentColor = SlateDark
            ),
            shape = RoundedCornerShape(12.dp),
            enabled = linkText.isNotBlank()
        ) {
            Icon(imageVector = Icons.Default.Unarchive, contentDescription = "Parse API Setup")
            Spacer(modifier = Modifier.width(6.dp))
            Text("Import and Parse Node", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun AddByManualFormView(
    viewModel: MainViewModel,
    onSuccess: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("VLESS") } // VMESS, VLESS, SHADOWSOCKS, TROJAN
    var address by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("443") }
    var uuid by remember { mutableStateOf("") }
    var network by remember { mutableStateOf("ws") } // tcp, ws, grpc
    var path by remember { mutableStateOf("") }
    var tls by remember { mutableStateOf(true) }
    var sni by remember { mutableStateOf("") }

    var expandedTypeDropdown by remember { mutableStateOf(false) }
    var expandedNetworkDropdown by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Name field
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Profile Name", color = SlateTextSecondary) },
            placeholder = { Text("E.g. Germany Premium Outbound", color = SlateTextSecondary.copy(alpha = 0.4f)) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CyberGreen, unfocusedBorderColor = SlateBorder),
            shape = RoundedCornerShape(10.dp)
        )

        // Type dropdown triggers
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                    value = type,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Protocol", color = SlateTextSecondary) },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { expandedTypeDropdown = true }) {
                            Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = "Select Type", tint = SlateTextSecondary)
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CyberGreen, unfocusedBorderColor = SlateBorder)
                )

                DropdownMenu(
                    expanded = expandedTypeDropdown,
                    onDismissRequest = { expandedTypeDropdown = false },
                    modifier = Modifier.background(SlateCard)
                ) {
                    listOf("VLESS", "VMESS", "SHADOWSOCKS", "TROJAN").forEach { t ->
                        DropdownMenuItem(
                            text = { Text(t, color = SlateTextPrimary) },
                            onClick = {
                                type = t
                                expandedTypeDropdown = false
                            }
                        )
                    }
                }
            }

            // Port field
            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it },
                label = { Text("Port", color = SlateTextSecondary) },
                modifier = Modifier.weight(0.7f),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CyberGreen, unfocusedBorderColor = SlateBorder),
                shape = RoundedCornerShape(10.dp)
            )
        }

        // Server Address
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text("Server Host / IP", color = SlateTextSecondary) },
            placeholder = { Text("de1.securenode.com", color = SlateTextSecondary.copy(alpha = 0.4f)) },
            modifier = Modifier.fillMaxWidth().testTag("manual_address_input"),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CyberGreen, unfocusedBorderColor = SlateBorder),
            shape = RoundedCornerShape(10.dp)
        )

        // UUID / Key / Pass parameters
        OutlinedTextField(
            value = uuid,
            onValueChange = { uuid = it },
            label = { Text(if (type == "SHADOWSOCKS" || type == "TROJAN") "Password / Crypto key" else "UUID Client ID", color = SlateTextSecondary) },
            placeholder = { Text("UUID string or access key text", color = SlateTextSecondary.copy(alpha = 0.4f)) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CyberGreen, unfocusedBorderColor = SlateBorder),
            shape = RoundedCornerShape(10.dp)
        )

        // Stream Network Type drop
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                    value = network.uppercase(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Transport", color = SlateTextSecondary) },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { expandedNetworkDropdown = true }) {
                            Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = "Select Network", tint = SlateTextSecondary)
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CyberGreen, unfocusedBorderColor = SlateBorder)
                )

                DropdownMenu(
                    expanded = expandedNetworkDropdown,
                    onDismissRequest = { expandedNetworkDropdown = false },
                    modifier = Modifier.background(SlateCard)
                ) {
                    listOf("ws", "tcp", "grpc").forEach { net ->
                        DropdownMenuItem(
                            text = { Text(net.uppercase(), color = SlateTextPrimary) },
                            onClick = {
                                network = net
                                expandedNetworkDropdown = false
                            }
                        )
                    }
                }
            }

            // TLS switch
            Row(
                modifier = Modifier
                    .weight(0.8f)
                    .height(56.dp)
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Text("TLS Security", fontSize = 11.sp, color = SlateTextSecondary)
                Checkbox(
                    checked = tls,
                    onCheckedChange = { tls = it },
                    colors = CheckboxDefaults.colors(checkedColor = CyberGreen, uncheckedColor = SlateBorder)
                )
            }
        }

        // Stream Path URL (Websocket or gRPC path)
        if (network == "ws" || network == "grpc") {
            OutlinedTextField(
                value = path,
                onValueChange = { path = it },
                label = { Text(if (network == "ws") "Websocket Path" else "gRPC Service Name", color = SlateTextSecondary) },
                placeholder = { Text(if (network == "ws") "/vless-ws" else "VmessGrpcService", color = SlateTextSecondary.copy(alpha = 0.4f)) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CyberGreen, unfocusedBorderColor = SlateBorder),
                shape = RoundedCornerShape(10.dp)
            )
        }

        // Server SNI Check (TLS)
        if (tls) {
            OutlinedTextField(
                value = sni,
                onValueChange = { sni = it },
                label = { Text("Server SNI Override", color = SlateTextSecondary) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CyberGreen, unfocusedBorderColor = SlateBorder),
                shape = RoundedCornerShape(10.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Save action
        Button(
            onClick = {
                val parsedPort = portText.toIntOrNull() ?: 443
                viewModel.addManualServer(
                    name = name,
                    type = type,
                    address = address,
                    port = parsedPort,
                    uuid = uuid,
                    network = network,
                    path = path,
                    tls = tls,
                    sni = sni
                )
                onSuccess("Created protocol profile successfully!")
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("save_manual_button"),
            colors = ButtonDefaults.buttonColors(
                containerColor = CyberGreen,
                contentColor = SlateDark
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(imageVector = Icons.Default.Save, contentDescription = "Save Configuration File")
            Spacer(modifier = Modifier.width(6.dp))
            Text("Save Profile", fontWeight = FontWeight.Bold)
        }
    }
}
