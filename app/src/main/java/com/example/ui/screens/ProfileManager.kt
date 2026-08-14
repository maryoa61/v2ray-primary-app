package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import com.example.data.ServerEntity
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun ProfileManager(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val servers by viewModel.servers.collectAsState()
    val activeServer by viewModel.activeServer.collectAsState()
    val isTestingPing by viewModel.isTestingPing.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()

    var showFormDialog by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<ServerEntity?>(null) }
    
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(SlateDark),
        snackbarHost = { 
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = SlateCard,
                    contentColor = SlateTextPrimary,
                    actionColor = CyberCyan,
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { 
                    editingProfile = null
                    showFormDialog = true 
                },
                containerColor = CyberGreen,
                contentColor = SlateDark,
                modifier = Modifier.testTag("add_profile_fab")
            ) {
                Icon(
                    imageVector = Icons.Default.Add, 
                    contentDescription = "Create New Connection Profile"
                )
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

            // Screen Header & Stats
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "V2RAY PROFILES",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = SlateTextSecondary,
                        letterSpacing = 1.5.sp
                    )
                    Text(
                        text = "Profile Manager",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = SlateTextPrimary
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Diagnostic Latency test
                    IconButton(
                        onClick = { viewModel.pingAllServers() },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = SlateSurface,
                            contentColor = CyberCyan
                        ),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = "Speed Test All Profiles",
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Bulk subscription Sync button
                    FilledTonalButton(
                        onClick = {
                            viewModel.syncAllSubscriptions()
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "Refreshing subscription profiles...",
                                    duration = SnackbarDuration.Short
                                )
                            }
                        },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = SlateSurface,
                            contentColor = CyberGreen
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.height(40.dp)
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
                                    contentDescription = "Sync Profiles",
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

            // Profile Summary Cards (Brief stats display)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ProfileStatCard(
                    title = "Total Profiles",
                    value = "${servers.size}",
                    icon = Icons.Default.Folder,
                    tint = CyberCyan,
                    modifier = Modifier.weight(1f)
                )
                ProfileStatCard(
                    title = "Active Profile",
                    value = activeServer?.type?.uppercase() ?: "NONE",
                    icon = Icons.Default.CheckCircle,
                    tint = CyberGreen,
                    modifier = Modifier.weight(1.2f)
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "SAVED CONFIGURATIONS",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = SlateTextSecondary,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Saved Profile list
            if (servers.isEmpty()) {
                EmptyProfilesState(onAddClick = { 
                    editingProfile = null
                    showFormDialog = true 
                })
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    items(servers, key = { it.id }) { server ->
                        ProfileItemCard(
                            server = server,
                            isActive = activeServer?.id == server.id,
                            isTestingPing = isTestingPing[server.id] ?: false,
                            onSelect = { 
                                viewModel.selectServer(server)
                                scope.launch {
                                    snackbarHostState.showSnackbar("Switched to configuration profile: ${server.name}")
                                }
                            },
                            onPing = { viewModel.triggerPing(server.id) },
                            onEdit = {
                                editingProfile = server
                                showFormDialog = true
                            },
                            onDelete = {
                                viewModel.deleteServer(server)
                                scope.launch {
                                    snackbarHostState.showSnackbar("Profile '${server.name}' deleted successfully.")
                                }
                            }
                        )
                    }
                }
            }
        }

        if (showFormDialog) {
            ProfileFormDialog(
                viewModel = viewModel,
                editingProfile = editingProfile,
                onDismiss = { showFormDialog = false },
                onSuccess = { msg ->
                    showFormDialog = false
                    scope.launch {
                        snackbarHostState.showSnackbar(msg)
                    }
                }
            )
        }
    }
}

@Composable
fun ProfileStatCard(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, SlateBorder)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(tint.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = title.uppercase(),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = SlateTextSecondary,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = value,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = SlateTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun ProfileItemCard(
    server: ServerEntity,
    isActive: Boolean,
    isTestingPing: Boolean,
    onSelect: () -> Unit,
    onPing: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val outlineCol = if (isActive) CyberGreen else SlateBorder
    val cardBg = if (isActive) SlateSurface else SlateCard

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("profile_item_${server.id}")
            .clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, outlineCol)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // First row: Protocol Tag, Name, TLS status, and Ping action
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Protocol Badge
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(width = 54.dp, height = 24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            when (server.type.uppercase()) {
                                "VLESS" -> CyberCyan.copy(alpha = 0.15f)
                                "VMESS" -> CyberGreen.copy(alpha = 0.15f)
                                "SHADOWSOCKS" -> CyberMagenta.copy(alpha = 0.15f)
                                "TROJAN" -> CyberGold.copy(alpha = 0.15f)
                                else -> SlateBorder
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
                            "TROJAN" -> CyberGold
                            else -> SlateTextPrimary
                        }
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                // Profile Name
                Text(
                    text = server.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = SlateTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (server.tls) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "SSL Secure Encrypted",
                        tint = CyberCyan,
                        modifier = Modifier
                            .size(14.dp)
                            .padding(end = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Ping Checker Badge
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
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Divider
            HorizontalDivider(
                color = SlateBorder.copy(alpha = 0.4f),
                thickness = 1.dp,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            // Second block: Profile Details (Address, Port, User ID, Transport Network)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    // Host/IP Address & Port
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = "Server Endpoint",
                            tint = SlateTextSecondary,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${server.address}:${server.port}",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = SlateTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // User ID / Password
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "User Identity",
                            tint = SlateTextSecondary,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        val truncatedUuid = if (server.uuid.length > 14) {
                            server.uuid.take(6) + "..." + server.uuid.takeLast(6)
                        } else {
                            server.uuid.ifEmpty { "N/A" }
                        }
                        Text(
                            text = "UID: $truncatedUuid",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = SlateTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Transport Protocol / Stream Network
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.SwapCalls,
                            contentDescription = "Transport Protocol",
                            tint = SlateTextSecondary,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Transport: ${server.network.uppercase()}${if (server.path.isNotEmpty()) " (${server.path})" else ""}",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = SlateTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Action buttons: Edit and Delete
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier
                            .size(36.dp)
                            .background(SlateSurface, CircleShape)
                            .border(1.dp, SlateBorder, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit Profile Settings",
                            tint = CyberCyan,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .size(36.dp)
                            .background(SlateSurface, CircleShape)
                            .border(1.dp, SlateBorder, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Delete Profile",
                            tint = CyberPink,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyProfilesState(onAddClick: () -> Unit) {
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
                imageVector = Icons.Default.FolderOpen,
                contentDescription = "No configs",
                tint = CyberGold,
                modifier = Modifier.size(36.dp)
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = "Profile Repository Empty",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = SlateTextPrimary
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Create secure customized server profiles or sync gateway links to start managing your private channels.",
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
            Text("Create Custom Profile", fontWeight = FontWeight.Bold)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileFormDialog(
    viewModel: MainViewModel,
    editingProfile: ServerEntity?,
    onDismiss: () -> Unit,
    onSuccess: (String) -> Unit
) {
    var activeTab by remember { mutableStateOf(if (editingProfile != null) 1 else 0) } // 0: Link, 1: Manual Input

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .wrapContentHeight()
            .testTag("profile_form_dialog"),
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
                        text = if (editingProfile != null) "UPDATE CONFIGURATION" else "NEW V2RAY PROFILE",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = SlateTextSecondary,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = if (editingProfile != null) "Edit Connection Profile" else "Create Custom Gateway",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = SlateTextPrimary,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    // Tab Row (only let link-import on creation, edit is form only)
                    if (editingProfile == null) {
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
                    }

                    if (activeTab == 0 && editingProfile == null) {
                        AddByShareLinkView(viewModel, onSuccess)
                    } else {
                        ProfileFormView(
                            viewModel = viewModel,
                            profile = editingProfile,
                            onSuccess = onSuccess
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

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
fun ProfileFormView(
    viewModel: MainViewModel,
    profile: ServerEntity?,
    onSuccess: (String) -> Unit
) {
    var name by remember { mutableStateOf(profile?.name ?: "") }
    var type by remember { mutableStateOf(profile?.type?.uppercase() ?: "VLESS") } // VMESS, VLESS, SHADOWSOCKS, TROJAN
    var address by remember { mutableStateOf(profile?.address ?: "") }
    var portText by remember { mutableStateOf(profile?.port?.toString() ?: "443") }
    var uuid by remember { mutableStateOf(profile?.uuid ?: "") }
    var network by remember { mutableStateOf(profile?.network ?: "ws") } // tcp, ws, grpc, h2
    var path by remember { mutableStateOf(profile?.path ?: "") }
    var tls by remember { mutableStateOf(profile?.tls ?: true) }
    var sni by remember { mutableStateOf(profile?.sni ?: "") }
    var security by remember { mutableStateOf(profile?.security ?: "none") }
    var flow by remember { mutableStateOf(profile?.flow ?: "") }
    var fingerprint by remember { mutableStateOf(profile?.fingerprint ?: "") }
    var publicKey by remember { mutableStateOf(profile?.publicKey ?: "") }
    var shortId by remember { mutableStateOf(profile?.shortId ?: "") }

    var expandedTypeDropdown by remember { mutableStateOf(false) }
    var expandedNetworkDropdown by remember { mutableStateOf(false) }
    var expandedSecurityDropdown by remember { mutableStateOf(false) }

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
            placeholder = { Text("E.g. Germany Anycast Hub", color = SlateTextSecondary.copy(alpha = 0.4f)) },
            modifier = Modifier.fillMaxWidth().testTag("profile_name_input"),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CyberGreen, unfocusedBorderColor = SlateBorder),
            shape = RoundedCornerShape(10.dp)
        )

        // Type Dropdown & Port Row
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.weight(1.2f)) {
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
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(0.8f).testTag("profile_port_input"),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CyberGreen, unfocusedBorderColor = SlateBorder),
                shape = RoundedCornerShape(10.dp)
            )
        }

        // Server Address
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text("Server Address (IP / Host)", color = SlateTextSecondary) },
            placeholder = { Text("E.g. one.one.one.one", color = SlateTextSecondary.copy(alpha = 0.4f)) },
            modifier = Modifier.fillMaxWidth().testTag("profile_address_input"),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CyberGreen, unfocusedBorderColor = SlateBorder),
            shape = RoundedCornerShape(10.dp)
        )

        // User ID / Password
        OutlinedTextField(
            value = uuid,
            onValueChange = { uuid = it },
            label = { Text(if (type == "SHADOWSOCKS" || type == "TROJAN") "Password / Crypto key" else "User ID (UUID)", color = SlateTextSecondary) },
            placeholder = { Text("E.g. 7a6e12e1-419b-4ff2-a4e1-22e3ad5b78ff", color = SlateTextSecondary.copy(alpha = 0.4f)) },
            modifier = Modifier.fillMaxWidth().testTag("profile_uid_input"),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CyberGreen, unfocusedBorderColor = SlateBorder),
            shape = RoundedCornerShape(10.dp)
        )

        // Transport (Stream Network) & TLS Security Row
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.weight(1.2f)) {
                OutlinedTextField(
                    value = network.uppercase(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Transport", color = SlateTextSecondary) },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { expandedNetworkDropdown = true }) {
                            Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = "Select Transport", tint = SlateTextSecondary)
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CyberGreen, unfocusedBorderColor = SlateBorder)
                )

                DropdownMenu(
                    expanded = expandedNetworkDropdown,
                    onDismissRequest = { expandedNetworkDropdown = false },
                    modifier = Modifier.background(SlateCard)
                ) {
                    listOf("ws", "tcp", "grpc", "h2").forEach { net ->
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
                Text("TLS", fontSize = 11.sp, color = SlateTextSecondary)
                Checkbox(
                    checked = tls,
                    onCheckedChange = { tls = it },
                    colors = CheckboxDefaults.colors(checkedColor = CyberGreen, uncheckedColor = SlateBorder)
                )
            }
        }

        // Stream Path URL (Websocket, HTTP/2, or gRPC path)
        if (network == "ws" || network == "grpc" || network == "h2") {
            OutlinedTextField(
                value = path,
                onValueChange = { path = it },
                label = { 
                    Text(
                        text = when (network) {
                            "ws" -> "Websocket Path"
                            "grpc" -> "gRPC Service Name"
                            else -> "HTTP Path"
                        }, 
                        color = SlateTextSecondary
                    ) 
                },
                placeholder = { Text(if (network == "ws") "/vless" else "VmessService", color = SlateTextSecondary.copy(alpha = 0.4f)) },
                modifier = Modifier.fillMaxWidth().testTag("profile_path_input"),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CyberGreen, unfocusedBorderColor = SlateBorder),
                shape = RoundedCornerShape(10.dp)
            )
        }

        // Server SNI Check & Security Settings (TLS)
        if (tls) {
            // Security Protocol Selector
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = security.uppercase(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Security Type", color = SlateTextSecondary) },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { expandedSecurityDropdown = true }) {
                            Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = "Select Security", tint = SlateTextSecondary)
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CyberGreen, unfocusedBorderColor = SlateBorder),
                    shape = RoundedCornerShape(10.dp)
                )

                DropdownMenu(
                    expanded = expandedSecurityDropdown,
                    onDismissRequest = { expandedSecurityDropdown = false },
                    modifier = Modifier.background(SlateCard)
                ) {
                    listOf("tls", "reality").forEach { sec ->
                        DropdownMenuItem(
                            text = { Text(sec.uppercase(), color = SlateTextPrimary) },
                            onClick = {
                                security = sec
                                expandedSecurityDropdown = false
                            }
                        )
                    }
                }
            }

            if (security == "reality") {
                // Public Key
                OutlinedTextField(
                    value = publicKey,
                    onValueChange = { publicKey = it },
                    label = { Text("Reality Public Key", color = SlateTextSecondary) },
                    placeholder = { Text("E.g. j2-D-XFf...", color = SlateTextSecondary.copy(alpha = 0.4f)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CyberGreen, unfocusedBorderColor = SlateBorder),
                    shape = RoundedCornerShape(10.dp)
                )

                // Short ID
                OutlinedTextField(
                    value = shortId,
                    onValueChange = { shortId = it },
                    label = { Text("Reality Short ID", color = SlateTextSecondary) },
                    placeholder = { Text("E.g. 6ba855a3", color = SlateTextSecondary.copy(alpha = 0.4f)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CyberGreen, unfocusedBorderColor = SlateBorder),
                    shape = RoundedCornerShape(10.dp)
                )

                // Flow Override
                OutlinedTextField(
                    value = flow,
                    onValueChange = { flow = it },
                    label = { Text("Flow (Optional)", color = SlateTextSecondary) },
                    placeholder = { Text("E.g. xtls-rprx-vision", color = SlateTextSecondary.copy(alpha = 0.4f)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CyberGreen, unfocusedBorderColor = SlateBorder),
                    shape = RoundedCornerShape(10.dp)
                )

                // Client Fingerprint
                OutlinedTextField(
                    value = fingerprint,
                    onValueChange = { fingerprint = it },
                    label = { Text("Client Fingerprint (Optional)", color = SlateTextSecondary) },
                    placeholder = { Text("E.g. chrome", color = SlateTextSecondary.copy(alpha = 0.4f)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CyberGreen, unfocusedBorderColor = SlateBorder),
                    shape = RoundedCornerShape(10.dp)
                )
            }

            OutlinedTextField(
                value = sni,
                onValueChange = { sni = it },
                label = { Text("Server SNI Override", color = SlateTextSecondary) },
                placeholder = { Text("E.g. cloudflare.com", color = SlateTextSecondary.copy(alpha = 0.4f)) },
                modifier = Modifier.fillMaxWidth().testTag("profile_sni_input"),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CyberGreen, unfocusedBorderColor = SlateBorder),
                shape = RoundedCornerShape(10.dp)
            )
        } else {
            // Set security to none if TLS is disabled
            security = "none"
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Save Action Button
        Button(
            onClick = {
                val parsedPort = portText.toIntOrNull() ?: 443
                if (profile != null) {
                    viewModel.updateManualServer(
                        id = profile.id,
                        name = name,
                        type = type,
                        address = address,
                        port = parsedPort,
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
                    onSuccess("Configuration profile updated successfully!")
                } else {
                    viewModel.addManualServer(
                        name = name,
                        type = type,
                        address = address,
                        port = parsedPort,
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
                    onSuccess("Profile created successfully!")
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("save_profile_button"),
            colors = ButtonDefaults.buttonColors(
                containerColor = CyberGreen,
                contentColor = SlateDark
            ),
            shape = RoundedCornerShape(12.dp),
            enabled = address.isNotBlank() && uuid.isNotBlank()
        ) {
            Icon(imageVector = Icons.Default.Save, contentDescription = "Save Configuration")
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (profile != null) "Update Profile" else "Save Profile", 
                fontWeight = FontWeight.Bold
            )
        }
    }
}
