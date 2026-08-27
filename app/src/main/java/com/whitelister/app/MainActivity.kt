package com.whitelister.app

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.whitelister.app.ui.theme.WhitelisterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WhitelisterTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WhitelisterScreen()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh accessibility status when returning from settings
    }
}

@Composable
fun WhitelisterScreen() {
    val context = LocalContext.current
    var isAccessibilityEnabled by remember {
        mutableStateOf(isAccessibilityServiceEnabled(context))
    }
    var reelsBlockingEnabled by remember {
        mutableStateOf(PreferencesManager.isReelsBlockingEnabled(context))
    }
    var feedFilteringEnabled by remember {
        mutableStateOf(PreferencesManager.isFeedFilteringEnabled(context))
    }
    var whitelistedAccounts by remember {
        mutableStateOf(PreferencesManager.getWhitelistedAccounts(context))
    }
    var showAddAccountDialog by remember { mutableStateOf(false) }
    var newAccountInput by remember { mutableStateOf("") }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isAccessibilityEnabled = isAccessibilityServiceEnabled(context)
                reelsBlockingEnabled = PreferencesManager.isReelsBlockingEnabled(context)
                feedFilteringEnabled = PreferencesManager.isFeedFilteringEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Whitelister",
            style = MaterialTheme.typography.headlineMedium
        )

        // Accessibility Service Status
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Accessibility Service",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = if (isAccessibilityEnabled) "Enabled" else "Disabled — Tap to enable",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isAccessibilityEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Button(
                    onClick = {
                        if (!isAccessibilityEnabled) {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isAccessibilityEnabled) "Service Active" else "Enable Service")
                }
            }
        }

        // Instagram Features
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Instagram",
                    style = MaterialTheme.typography.titleMedium
                )

                // Remove Reels Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Remove Reels", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Blocks scrolling between reels",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = reelsBlockingEnabled,
                        onCheckedChange = { enabled ->
                            reelsBlockingEnabled = enabled
                            PreferencesManager.setReelsBlockingEnabled(context, enabled)
                        },
                        enabled = isAccessibilityEnabled
                    )
                }

                HorizontalDivider()

                // Whitelist Feed Section
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Whitelist Feed", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Only show posts from these accounts",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = feedFilteringEnabled,
                            onCheckedChange = { enabled ->
                                feedFilteringEnabled = enabled
                                PreferencesManager.setFeedFilteringEnabled(context, enabled)
                            },
                            enabled = isAccessibilityEnabled
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(
                            onClick = { showAddAccountDialog = true },
                            enabled = isAccessibilityEnabled && feedFilteringEnabled
                        ) {
                            Text("Add")
                        }
                    }

                    // List of whitelisted accounts
                    whitelistedAccounts.forEach { account ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("@$account")
                            TextButton(
                                onClick = {
                                    whitelistedAccounts = whitelistedAccounts - account
                                    PreferencesManager.setWhitelistedAccounts(context, whitelistedAccounts)
                                },
                                enabled = isAccessibilityEnabled
                            ) {
                                Text("Remove")
                            }
                        }
                    }

                    if (whitelistedAccounts.isEmpty()) {
                        Text(
                            "No accounts whitelisted",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    // Add Account Dialog (disabled for now)
    if (showAddAccountDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddAccountDialog = false
                newAccountInput = ""
            },
            title = { Text("Add Account") },
            text = {
                OutlinedTextField(
                    value = newAccountInput,
                    onValueChange = { newAccountInput = it },
                    label = { Text("Instagram username") },
                    placeholder = { Text("username") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val username = newAccountInput.trim().removePrefix("@")
                        if (username.isNotEmpty()) {
                            whitelistedAccounts = whitelistedAccounts + username
                            PreferencesManager.setWhitelistedAccounts(context, whitelistedAccounts)
                            newAccountInput = ""
                            showAddAccountDialog = false
                        }
                    }
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAddAccountDialog = false
                        newAccountInput = ""
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun isAccessibilityServiceEnabled(context: android.content.Context): Boolean {
    val service = ComponentName(context, WhitelistAccessibilityService::class.java)
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    val colonSplitter = TextUtils.SimpleStringSplitter(':')
    colonSplitter.setString(enabledServices)

    while (colonSplitter.hasNext()) {
        val componentName = colonSplitter.next()
        if (ComponentName.unflattenFromString(componentName) == service) {
            return true
        }
    }

    return false
}
