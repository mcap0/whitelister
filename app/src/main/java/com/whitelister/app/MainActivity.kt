package com.whitelister.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
                    AppRoot()
                }
            }
        }
    }
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    var consentAccepted by remember {
        mutableStateOf(PreferencesManager.isConsentAccepted(context))
    }
    var showPolicy by remember { mutableStateOf(false) }

    when {
        !consentAccepted -> ConsentScreen(
            onAccept = {
                PreferencesManager.setConsentAccepted(context, true)
                consentAccepted = true
            },
            onDecline = { (context as? ComponentActivity)?.finish() },
            onViewPolicy = { showPolicy = true }
        )
        showPolicy -> PolicyScreen(onClose = { showPolicy = false })
        else -> WhitelisterScreen(onOpenPolicy = { showPolicy = true })
    }
}

@Composable
fun ConsentScreen(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onViewPolicy: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = getString(context, R.string.consent_title),
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = getString(context, R.string.consent_body),
            style = MaterialTheme.typography.bodyMedium
        )
        Button(
            onClick = onViewPolicy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(getString(context, R.string.view_privacy_policy))
        }
        Spacer(modifier = Modifier.weight(1f))
        Button(
            onClick = onAccept,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(getString(context, R.string.consent_accept))
        }
        OutlinedButton(
            onClick = onDecline,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(getString(context, R.string.consent_decline))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PolicyScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        TopAppBar(
            title = { Text(getString(context, R.string.privacy_policy_title)) },
            actions = {
                IconButton(onClick = onClose) {
                    Text("Close")
                }
            }
        )
        Text(
            text = getString(context, R.string.privacy_policy_text),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhitelisterScreen(onOpenPolicy: () -> Unit) {
    val context = LocalContext.current
    var isAccessibilityEnabled by remember {
        mutableStateOf(isAccessibilityServiceEnabled(context))
    }
    var reelsBlockingEnabled by remember {
        mutableStateOf(PreferencesManager.isReelsBlockingEnabled(context))
    }
    var hidePromotedEnabled by remember {
        mutableStateOf(PreferencesManager.isHidePromotedEnabled(context))
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isAccessibilityEnabled = isAccessibilityServiceEnabled(context)
                reelsBlockingEnabled = PreferencesManager.isReelsBlockingEnabled(context)
                hidePromotedEnabled = PreferencesManager.isHidePromotedEnabled(context)
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
    ) {
        TopAppBar(
            title = { Text("Whitelister") },
            actions = {
                IconButton(onClick = onOpenPolicy) {
                    Icon(Icons.Filled.Info, contentDescription = "Privacy & info")
                }
            }
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
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

                    // Hide Sponsored & Suggested Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                getString(context, R.string.hide_promoted_title),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                getString(context, R.string.hide_promoted_summary),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = hidePromotedEnabled,
                            onCheckedChange = { enabled ->
                                hidePromotedEnabled = enabled
                                PreferencesManager.setHidePromotedEnabled(context, enabled)
                            },
                            enabled = isAccessibilityEnabled
                        )
                    }
                }
            }
        }
    }
}

private fun getString(context: Context, resId: Int): String = context.getString(resId)

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
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
