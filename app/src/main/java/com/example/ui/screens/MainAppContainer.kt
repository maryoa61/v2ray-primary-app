package com.example.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.theme.*

enum class AppTab(val title: String, val icon: ImageVector, val testTag: String) {
    HOME("Home", Icons.Default.Home, "tab_home"),
    SERVERS("Profiles", Icons.Default.Dns, "tab_servers"),
    LOGS("Terminal", Icons.Default.Terminal, "tab_logs"),
    SETTINGS("Settings", Icons.Default.Settings, "tab_settings")
}

@Composable
fun MainAppContainer(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(AppTab.HOME) }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(SlateDark),
        contentWindowInsets = WindowInsets.safeDrawing, // Automatically handles edge-to-edge status values
        bottomBar = {
            NavigationBar(
                containerColor = SlateSurface,
                tonalElevation = 8.dp,
                windowInsets = WindowInsets.navigationBars, // Respects bottom system gesture bar safely
                modifier = Modifier.testTag("app_bottom_bar")
            ) {
                AppTab.values().forEach { tab ->
                    val isSelected = selectedTab == tab
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { selectedTab = tab },
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.title,
                                tint = if (isSelected) CyberGreen else SlateTextSecondary
                            )
                        },
                        label = {
                            Text(
                                text = tab.title,
                                color = if (isSelected) SlateTextPrimary else SlateTextSecondary,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = CyberGreen,
                            unselectedIconColor = SlateTextSecondary,
                            indicatorColor = CyberGreen.copy(alpha = 0.15f)
                        ),
                        modifier = Modifier.testTag(tab.testTag)
                    )
                }
            }
        },
        containerColor = SlateDark
    ) { innerPadding ->
        Crossfade(
            targetState = selectedTab,
            animationSpec = tween(280),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            label = "tab_fade"
        ) { tab ->
            when (tab) {
                AppTab.HOME -> HomeScreen(
                    viewModel = viewModel,
                    onNavigateToServers = { selectedTab = AppTab.SERVERS }
                )
                AppTab.SERVERS -> ProfileManager(
                    viewModel = viewModel
                )
                AppTab.LOGS -> LogsScreen(
                    viewModel = viewModel
                )
                AppTab.SETTINGS -> SettingsScreen(
                    viewModel = viewModel
                )
            }
        }
    }
}
