package com.example.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ServerEntity
import com.example.service.SpeedState
import com.example.service.VpnState
import com.example.ui.theme.*
import java.util.Locale

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    onNavigateToServers: () -> Unit
) {
    val vpnState by viewModel.vpnState.collectAsState()
    val speedState by viewModel.speedState.collectAsState()
    val activeServer by viewModel.activeServer.collectAsState()
    val durationSec by viewModel.connectionDuration.collectAsState()
    val routingMode by viewModel.routingMode.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SlateDark)
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        // Title Header
        Text(
            text = "V2RAY SECURE CORE",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = SlateTextSecondary,
            letterSpacing = 2.sp,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            text = "V2Ray Dan Pro",
            fontSize = 26.sp,
            fontWeight = FontWeight.ExtraBold,
            color = SlateTextPrimary,
            letterSpacing = 0.5.sp
        )

        Spacer(modifier = Modifier.weight(0.81f))

        // Connection Core Power Toggle Button
        VpnToggleButton(
            vpnState = vpnState,
            onClick = { viewModel.toggleVpn() }
        )

        Spacer(modifier = Modifier.weight(1f))

        // Current Connected Duration
        ConnectionDurationView(durationSec = durationSec, vpnState = vpnState)

        Spacer(modifier = Modifier.height(30.dp))

        // Bandwidth Speed Indicators Row
        SpeedMeterGrid(speedState = speedState)

        Spacer(modifier = Modifier.height(20.dp))

        // Selected Node Card Selector
        SelectedNodeCard(
            activeServer = activeServer,
            onClick = onNavigateToServers,
            routingMode = routingMode
        )

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun VpnToggleButton(
    vpnState: VpnState,
    onClick: () -> Unit
) {
    // Elegant pulsing and glowing animations
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (vpnState == VpnState.CONNECTED) 1.08f else 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EasyInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = if (vpnState == VpnState.CONNECTED) 0.65f else 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    val coreBgColor by animateColorAsState(
        targetValue = when (vpnState) {
            VpnState.CONNECTED -> CyberGreen
            VpnState.CONNECTING -> CyberGold
            VpnState.DISCONNECTING -> CyberPink
            VpnState.ERROR -> CyberPink
            else -> ActiveButtonBg
        },
        animationSpec = tween(500),
        label = "coreBg"
    )

    val iconColor by animateColorAsState(
        targetValue = when (vpnState) {
            VpnState.CONNECTED -> SlateDark
            VpnState.CONNECTING -> SlateDark
            else -> CyberGreen
        },
        animationSpec = tween(500),
        label = "iconColor"
    )

    val strokeColor by animateColorAsState(
        targetValue = when (vpnState) {
            VpnState.CONNECTED -> CyberGreen.copy(alpha = 0.8f)
            VpnState.CONNECTING -> CyberGold.copy(alpha = 0.8f)
            VpnState.ERROR -> CyberPink
            else -> SlateBorder
        },
        animationSpec = tween(500),
        label = "stroke"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(190.dp)
            .testTag("vpn_power_toggle")
            .clickable(onClick = onClick)
    ) {
        // Soft outer glowing background
        Box(
            modifier = Modifier
                .size(175.dp)
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                coreBgColor.copy(alpha = glowAlpha),
                                Color.Transparent
                            ),
                            center = center,
                            radius = size.minDimension / 2 * pulseScale
                        )
                    )
                }
        )

        // Outer progress ring path
        Box(
            modifier = Modifier
                .size(160.dp)
                .drawBehind {
                    drawCircle(
                        color = strokeColor,
                        radius = size.minDimension / 2 - 4.dp.toPx(),
                        style = Stroke(
                            width = 3.dp.toPx(),
                            pathEffect = null
                        )
                    )
                }
        )

        // Floating center toggle button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(126.dp)
                .shadow(elevation = 16.dp, shape = CircleShape, clip = false)
                .background(coreBgColor, CircleShape)
                .border(2.dp, SlateDark.copy(alpha = 0.15f), CircleShape)
                .clip(CircleShape)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PowerSettingsNew,
                    contentDescription = "Power Connection",
                    tint = iconColor,
                    modifier = Modifier.size(38.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = when (vpnState) {
                        VpnState.CONNECTED -> "CONNECTED"
                        VpnState.CONNECTING -> "CONNECTING"
                        VpnState.DISCONNECTING -> "STOPPING"
                        VpnState.ERROR -> "RETRY"
                        else -> "TAP CORE"
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = iconColor,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

@Composable
fun ConnectionDurationView(
    durationSec: Long,
    vpnState: VpnState
) {
    val durationText = if (vpnState == VpnState.CONNECTED) {
        val hrs = durationSec / 3600
        val mins = (durationSec % 3600) / 60
        val secs = durationSec % 60
        String.format(Locale.getDefault(), "%02d:%02d:%02d", hrs, mins, secs)
    } else if (vpnState == VpnState.CONNECTING) {
        "SECURE TUNNELING..."
    } else {
        "OFFLINE DECK"
    }

    val badgeColor = when (vpnState) {
        VpnState.CONNECTED -> CyberGreen.copy(alpha = 0.15f)
        VpnState.CONNECTING -> CyberGold.copy(alpha = 0.15f)
        else -> SlateSurface
    }

    val textCol = when (vpnState) {
        VpnState.CONNECTED -> CyberGreen
        VpnState.CONNECTING -> CyberGold
        else -> SlateTextSecondary
    }

    Box(
        modifier = Modifier
            .background(badgeColor, RoundedCornerShape(10.dp))
            .border(1.dp, textCol.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        if (vpnState == VpnState.CONNECTED) CyberGreen else if (vpnState == VpnState.CONNECTING) CyberGold else SlateTextSecondary,
                        CircleShape
                    )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = durationText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = textCol,
                letterSpacing = 1.sp
            )
        }
    }
}

@Composable
fun SpeedMeterGrid(speedState: SpeedState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Download Box
        Box(
            modifier = Modifier
                .weight(1f)
                .background(SlateSurface, RoundedCornerShape(24.dp))
                .border(1.dp, SlateBorder, RoundedCornerShape(24.dp))
                .padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxHeight()
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .background(CyberGreen.copy(alpha = 0.1f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowDownward,
                        contentDescription = "Download speed",
                        tint = CyberGreen,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "DOWNLOAD",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = CyberGreen,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = speedState.downloadSpeed,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = SlateTextPrimary
                    )
                }
            }
        }

        // Upload Box
        Box(
            modifier = Modifier
                .weight(1f)
                .background(SlateSurface, RoundedCornerShape(24.dp))
                .border(1.dp, SlateBorder, RoundedCornerShape(24.dp))
                .padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxHeight()
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .background(CyberGreen.copy(alpha = 0.1f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = "Upload speed",
                        tint = CyberGreen,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "UPLOAD",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = CyberGreen,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = speedState.uploadSpeed,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = SlateTextPrimary
                    )
                }
            }
        }
    }
}

@Composable
fun SelectedNodeCard(
    activeServer: ServerEntity?,
    onClick: () -> Unit,
    routingMode: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("node_selector_card")
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        shape = RoundedCornerShape(20.dp),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Row(
            modifier = Modifier
                .padding(18.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Server icon badge
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(46.dp)
                    .background(CyberGreen.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                    .border(1.dp, CyberGreen.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            ) {
                Icon(
                    imageVector = Icons.Default.Dns,
                    contentDescription = "Active Server Mode",
                    tint = CyberGreen,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "ACTIVE GATEWAY",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = SlateTextSecondary,
                        letterSpacing = 1.sp
                    )
                }
                Text(
                    text = activeServer?.name ?: "No Node Selected",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = SlateTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (activeServer != null) {
                        "${activeServer.type} • ${activeServer.address}:${activeServer.port} [Mode: $routingMode]"
                    } else {
                        "Tap below to connect or select a server"
                    },
                    fontSize = 11.sp,
                    color = SlateTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Arrow action indicator
            Icon(
                imageVector = Icons.Default.NavigateNext,
                contentDescription = "Toggle connection screen",
                tint = SlateTextSecondary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

// Ease curves for pulsing controls
val EasyInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
