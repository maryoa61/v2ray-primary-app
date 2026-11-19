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
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.example.data.SubscriptionEntity
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val subscriptions by viewModel.subscriptions.collectAsState()
    val routingMode by viewModel.routingMode.collectAsState()
    val dnsServer by viewModel.dnsServer.collectAsState()
    val runtimeSettings by viewModel.runtimeSettings.collectAsState()

    var showAddSubscription by remember { mutableStateOf(false) }
    var tempDnsText by remember { mutableStateOf(dnsServer) }
    var fragmentExpanded by remember { mutableStateOf(true) }
    var fragmentEditField by remember { mutableStateOf<String?>(null) }
    var fragmentEditValue by remember { mutableStateOf("") }
    fun isValidRange(value: String, minValue: Int, maxValue: Int, requireRange: Boolean = false): Boolean {
        val parts = value.trim().split('-')
        if (parts.size !in 1..2 || (requireRange && parts.size != 2)) return false
        val from = parts[0].trim().toIntOrNull() ?: return false
        val to = if (parts.size == 1) from else parts[1].trim().toIntOrNull() ?: return false
        return from in minValue..maxValue && to in from..maxValue
    }
    val fragmentEditValid = when (fragmentEditField) {
        "packets" -> fragmentEditValue.equals("tlshello", ignoreCase = true) ||
            isValidRange(fragmentEditValue, minValue = 1, maxValue = 100, requireRange = true)
        "length" -> isValidRange(fragmentEditValue, minValue = 1, maxValue = 65_535)
        "interval" -> isValidRange(fragmentEditValue, minValue = 0, maxValue = 60_000)
        "maxSplit" -> fragmentEditValue.toIntOrNull()?.let { it in 1..1_024 } == true
        else -> false
    }
    val fragmentEditLabel = when (fragmentEditField) {
        "packets" -> "بسته‌های مورد استفاده برای قطعه‌بندی (Fragment)"
        "length" -> "طول قطعه (حداقل-حداکثر)"
        "interval" -> "فاصله قطعه‌ها (حداقل-حداکثر)"
        "maxSplit" -> "حداکثر تعداد قطعه‌ها"
        else -> "تنظیم Fragment"
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SlateDark)
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Header Title
        Column {
            Text(
                text = "CONFIG SYSTEM",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = SlateTextSecondary,
                letterSpacing = 1.5.sp
            )
            Text(
                text = "Settings & Feed",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = SlateTextPrimary
            )
        }

        // Section 1: Proxy Routing Rules
        Text(
            text = "PROXY ROUTING STRATEGY",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = CyberCyan,
            letterSpacing = 0.5.sp
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SlateSurface),
            border = CardDefaults.outlinedCardBorder(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(
                    Pair("Bypass LAN & Mainland", "Routes domestic and local traffic directly. Proxies overseas traffic."),
                    Pair("Global Proxy", "Routes all connection requests through the selected secure V2Ray tunnel."),
                    Pair("Direct Routing", "Bypasses proxy endpoints completely. Uses local ISP gateway directly.")
                ).forEach { (modeTitle, modeDesc) ->
                    val isSelected = routingMode == modeTitle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) SlateCard else Color.Transparent)
                            .clickable { viewModel.setRoutingMode(modeTitle) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { viewModel.setRoutingMode(modeTitle) },
                            colors = RadioButtonDefaults.colors(selectedColor = CyberCyan, unselectedColor = SlateBorder)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = modeTitle,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) CyberCyan else SlateTextPrimary
                            )
                            Text(
                                text = modeDesc,
                                fontSize = 11.sp,
                                color = SlateTextSecondary
                            )
                        }
                    }
                }
            }
        }

        // Section 2: Core DNS Settings
        Text(
            text = "DNS NAME RESOLUTION IP",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = CyberCyan,
            letterSpacing = 0.5.sp
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SlateSurface),
            border = CardDefaults.outlinedCardBorder(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = tempDnsText,
                    onValueChange = { tempDnsText = it },
                    label = { Text("IPv4 Resolver Address", color = SlateTextSecondary) },
                    placeholder = { Text("E.g. 1.1.1.1", color = SlateTextSecondary.copy(alpha = 0.4f)) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("dns_input_field"),
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, color = SlateTextPrimary),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyberCyan,
                        unfocusedBorderColor = SlateBorder
                    ),
                    shape = RoundedCornerShape(10.dp)
                )

                Button(
                    onClick = {
                        if (tempDnsText.isNotBlank()) {
                            viewModel.setDnsServer(tempDnsText.trim())
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = SlateDark),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .height(52.dp)
                        .padding(top = 8.dp)
                        .testTag("save_dns_button")
                ) {
                    Text("Apply", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
        Text(
            "App DNS queries use this numeric IPv4 resolver through the proxy tunnel. For domain-based proxy hosts, Xray bootstraps the server address separately with direct, encrypted Cloudflare DoH (1.1.1.1:443); this setting does not change that resolver, and it must be reachable before the tunnel starts. A private LAN resolver may not be reachable from the proxy server.",
            fontSize = 11.sp,
            color = SlateTextSecondary
        )

        // Section 3: Subscription Feeds Control
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "SUBSCRIPTION SOURCE FEEDS",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = CyberCyan,
                letterSpacing = 0.5.sp
            )

            IconButton(
                onClick = { showAddSubscription = true },
                colors = IconButtonDefaults.iconButtonColors(containerColor = SlateSurface, contentColor = CyberGreen),
                modifier = Modifier
                    .size(28.dp)
                    .testTag("add_subscription_icon")
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "Add Feed Link", modifier = Modifier.size(16.dp))
            }
        }

        if (subscriptions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, SlateBorder, RoundedCornerShape(16.dp))
                    .background(SlateSurface)
                    .clickable { showAddSubscription = true }
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(imageVector = Icons.Default.CloudQueue, contentDescription = "No Feeds", tint = SlateTextSecondary.copy(alpha = 0.6f))
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("No feed feeds linked. Tap to add.", fontSize = 11.sp, color = SlateTextSecondary)
                }
            }
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                subscriptions.forEach { sub ->
                    SubscriptionItemRow(
                        subscription = sub,
                        onDelete = { viewModel.deleteSubscription(sub) }
                    )
                }
            }
        }

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("fragment_settings_section"),
                colors = CardDefaults.cardColors(containerColor = SlateSurface),
                border = CardDefaults.outlinedCardBorder(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { fragmentExpanded = !fragmentExpanded }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "تنظیمات قطعه‌بندی بسته‌ها (Fragment)",
                            modifier = Modifier.weight(1f),
                            color = SlateTextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Start
                        )
                        Icon(
                            imageVector = if (fragmentExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (fragmentExpanded) "بستن تنظیمات Fragment" else "باز کردن تنظیمات Fragment",
                            tint = SlateTextSecondary
                        )
                    }
                    AnimatedVisibility(visible = fragmentExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "فعال کردن Fragment",
                                    modifier = Modifier.weight(1f),
                                    color = SlateTextPrimary,
                                    fontSize = 15.sp,
                                    textAlign = TextAlign.Start
                                )
                                Switch(
                                    checked = runtimeSettings.fragmentEnabled,
                                    onCheckedChange = viewModel::setFragmentEnabled,
                                    modifier = Modifier.testTag("fragment_enabled_toggle")
                                )
                            }
                            FragmentValueRow(
                                label = "بسته‌های مورد استفاده برای قطعه‌بندی (Fragment)",
                                value = runtimeSettings.fragmentPackets,
                                testTag = "fragment_packets_row"
                            ) {
                                fragmentEditField = "packets"
                                fragmentEditValue = runtimeSettings.fragmentPackets
                            }
                            FragmentValueRow(
                                label = "طول قطعه (حداقل-حداکثر)",
                                value = runtimeSettings.fragmentLength,
                                testTag = "fragment_length_row"
                            ) {
                                fragmentEditField = "length"
                                fragmentEditValue = runtimeSettings.fragmentLength
                            }
                            FragmentValueRow(
                                label = "فاصله قطعه‌ها (حداقل-حداکثر)",
                                value = runtimeSettings.fragmentInterval,
                                testTag = "fragment_interval_row"
                            ) {
                                fragmentEditField = "interval"
                                fragmentEditValue = runtimeSettings.fragmentInterval
                            }
                            FragmentValueRow(
                                label = "حداکثر تعداد قطعه‌ها",
                                value = runtimeSettings.fragmentMaxSplit,
                                testTag = "fragment_max_split_row"
                            ) {
                                fragmentEditField = "maxSplit"
                                fragmentEditValue = runtimeSettings.fragmentMaxSplit
                            }
                            Text(
                                "برای اعمال تنظیمات، پس از ذخیره اتصال را دوباره برقرار کنید.",
                                modifier = Modifier.fillMaxWidth(),
                                color = SlateTextSecondary,
                                fontSize = 11.sp,
                                textAlign = TextAlign.Start
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }

    if (showAddSubscription) {
        AddSubscriptionDialog(
            onDismiss = { showAddSubscription = false },
            onSave = { name, url ->
                viewModel.addSubscriptionUrl(name, url)
                showAddSubscription = false
            }
        )
    }

    if (fragmentEditField != null) {
        val field = fragmentEditField
        val helper = when (field) {
            "packets" -> "tlshello یا بازه‌ای مانند 1-3"
            "length" -> "عدد یا بازه‌ای مانند 50-100"
            "interval" -> "عدد یا بازه‌ای مانند 10-20"
            else -> "عدد بین 1 تا 1024"
        }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            AlertDialog(
                onDismissRequest = { fragmentEditField = null },
                title = { Text("ویرایش $fragmentEditLabel", textAlign = TextAlign.Start) },
                text = {
                    OutlinedTextField(
                        value = fragmentEditValue,
                        onValueChange = { fragmentEditValue = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(fragmentEditLabel) },
                        supportingText = { Text(helper) },
                        isError = !fragmentEditValid,
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyberCyan,
                            unfocusedBorderColor = SlateBorder
                        )
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = fragmentEditValid,
                        onClick = {
                            if (field != null && fragmentEditValid) {
                                viewModel.setFragmentParameters(
                                    packets = if (field == "packets") fragmentEditValue.trim() else runtimeSettings.fragmentPackets,
                                    length = if (field == "length") fragmentEditValue.trim() else runtimeSettings.fragmentLength,
                                    interval = if (field == "interval") fragmentEditValue.trim() else runtimeSettings.fragmentInterval,
                                    maxSplit = if (field == "maxSplit") fragmentEditValue.trim() else runtimeSettings.fragmentMaxSplit
                                )
                                fragmentEditField = null
                            }
                        }
                    ) { Text("ذخیره") }
                },
                dismissButton = {
                    TextButton(onClick = { fragmentEditField = null }) { Text("انصراف") }
                },
                containerColor = SlateSurface,
                titleContentColor = SlateTextPrimary,
                textContentColor = SlateTextPrimary
            )
        }
    }
}

@Composable
private fun FragmentValueRow(
    label: String,
    value: String,
    testTag: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp)
            .testTag(testTag),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = label,
            color = SlateTextPrimary,
            fontSize = 14.sp,
            textAlign = TextAlign.Start
        )
        Text(
            text = value,
            color = SlateTextSecondary,
            fontSize = 12.sp,
            textAlign = TextAlign.Start
        )
    }
}

@Composable
fun SubscriptionItemRow(
    subscription: SubscriptionEntity,
    onDelete: () -> Unit
) {
    val dateText = remember(subscription.lastUpdated) {
        val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        sdf.format(Date(subscription.lastUpdated))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        border = CardDefaults.outlinedCardBorder(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(36.dp)
                    .background(CyberCyan.copy(alpha = 0.1f), CircleShape)
            ) {
                Icon(imageVector = Icons.Default.Language, contentDescription = "Sub Link", tint = CyberCyan, modifier = Modifier.size(16.dp))
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = subscription.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = SlateTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subscription.url,
                    fontSize = 11.sp,
                    color = SlateTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Last Synced: $dateText",
                    fontSize = 10.sp,
                    color = SlateTextSecondary.copy(alpha = 0.7f)
                )
            }

            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(imageVector = Icons.Default.DeleteSweep, contentDescription = "Remove subscription URL feed", tint = CyberPink, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSubscriptionDialog(
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var nameText by remember { mutableStateOf("") }
    var urlText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .wrapContentHeight(),
        content = {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = SlateSurface,
                border = BorderStroke(1.dp, SlateBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(text = "Link Subscription Feed", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = SlateTextPrimary)

                    OutlinedTextField(
                        value = nameText,
                        onValueChange = { nameText = it },
                        label = { Text("Feed Name", color = SlateTextSecondary) },
                        placeholder = { Text("E.g. Fast Core Premium Feed", color = SlateTextSecondary.copy(alpha = 0.4f)) },
                        modifier = Modifier.fillMaxWidth().testTag("sub_name_input"),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CyberCyan, unfocusedBorderColor = SlateBorder)
                    )

                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        label = { Text("Subscription URL", color = SlateTextSecondary) },
                        placeholder = { Text("https://myvpnfeed.link/get", color = SlateTextSecondary.copy(alpha = 0.4f)) },
                        modifier = Modifier.fillMaxWidth().testTag("sub_url_input"),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CyberCyan, unfocusedBorderColor = SlateBorder)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = SlateTextSecondary)) {
                            Text("Cancel")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (nameText.isNotBlank() && urlText.isNotBlank()) {
                                    onSave(nameText.trim(), urlText.trim())
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = SlateDark),
                            shape = RoundedCornerShape(8.dp),
                            enabled = nameText.isNotBlank() && urlText.isNotBlank()
                        ) {
                            Text("Configure", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    )
}
