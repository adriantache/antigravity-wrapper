package com.adriantache.antigravitywrapper.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adriantache.antigravitywrapper.data.PreferencesManager

@Composable
fun SettingsDialog(
    preferencesManager: PreferencesManager,
    currentUrl: String,
    onDismiss: () -> Unit,
    onSaveAndConnect: (newUrl: String) -> Unit,
    onClearCache: () -> Unit
) {
    var urlInput by remember { mutableStateOf(currentUrl) }
    var isDesktop by remember { mutableStateOf(preferencesManager.isDesktopMode) }
    var isKeepScreenOn by remember { mutableStateOf(preferencesManager.isKeepScreenOn) }
    var isPullToRefresh by remember { mutableStateOf(preferencesManager.isPullToRefreshEnabled) }
    var isDoubleBack by remember { mutableStateOf(preferencesManager.isDoubleBackExitEnabled) }
    var isNativeComposer by remember { mutableStateOf(preferencesManager.isNativeComposerEnabled) }
    var isShowVoiceButton by remember { mutableStateOf(preferencesManager.isShowVoiceButtonEnabled) }
    var recentUrls by remember { mutableStateOf(preferencesManager.recentUrls) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Session Settings",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Server URL input
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text("Remote Antigravity URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (urlInput.isNotEmpty()) {
                            IconButton(onClick = { urlInput = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear URL")
                            }
                        }
                    }
                )

                // Quick preset button for official web interface
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { urlInput = PreferencesManager.DEFAULT_URL },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Reset to Default URL", fontSize = 12.sp)
                    }
                }

                // Recent URLs list
                if (recentUrls.isNotEmpty()) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Recent Sessions",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            IconButton(
                                onClick = {
                                    preferencesManager.clearRecentUrls()
                                    recentUrls = emptyList()
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Clear history",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 110.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            recentUrls.forEach { recent ->
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { urlInput = recent }
                                        .padding(vertical = 4.dp),
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.History,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = recent,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp
                                            ),
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = DividerDefaults.color.copy(alpha = 0.4f))

                // Toggle: Native Input Bar
                SettingToggleRow(
                    title = "Native Input Bar",
                    subtitle = "Smooth native typing, larger Send button & model picker",
                    checked = isNativeComposer,
                    onCheckedChange = { isNativeComposer = it }
                )

                // Toggle: Show Voice Button
                SettingToggleRow(
                    title = "Show Voice Button",
                    subtitle = "Display voice memo button in the input bar",
                    checked = isShowVoiceButton,
                    onCheckedChange = { isShowVoiceButton = it }
                )

                // Toggle: Double Back to Exit
                SettingToggleRow(
                    title = "Intercept Back Gestures",
                    subtitle = "Send Escape to web session & require double-back to exit",
                    checked = isDoubleBack,
                    onCheckedChange = { isDoubleBack = it }
                )

                // Toggle: Pull to Refresh
                SettingToggleRow(
                    title = "Pull to Refresh",
                    subtitle = "Swipe down at top of page to reload",
                    checked = isPullToRefresh,
                    onCheckedChange = { isPullToRefresh = it }
                )

                // Toggle: Keep Screen On
                SettingToggleRow(
                    title = "Keep Screen On",
                    subtitle = "Prevent display timeout while viewing session",
                    checked = isKeepScreenOn,
                    onCheckedChange = { isKeepScreenOn = it }
                )

                // Toggle: Desktop Mode
                SettingToggleRow(
                    title = "Desktop User-Agent",
                    subtitle = "Request desktop interface layout",
                    checked = isDesktop,
                    onCheckedChange = { isDesktop = it }
                )

                // Cache cleaner
                TextButton(
                    onClick = onClearCache,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Clear WebView Cache & Cookies", fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    preferencesManager.isDesktopMode = isDesktop
                    preferencesManager.isKeepScreenOn = isKeepScreenOn
                    preferencesManager.isPullToRefreshEnabled = isPullToRefresh
                    preferencesManager.isDoubleBackExitEnabled = isDoubleBack
                    preferencesManager.isNativeComposerEnabled = isNativeComposer
                    preferencesManager.isShowVoiceButtonEnabled = isShowVoiceButton

                    var finalUrl = urlInput.trim()
                    if (!finalUrl.startsWith("http://") && !finalUrl.startsWith("https://")) {
                        finalUrl = "http://$finalUrl"
                    }
                    preferencesManager.serverUrl = finalUrl
                    onSaveAndConnect(finalUrl)
                }
            ) {
                Text("Save & Connect")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
