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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
                    MainScreen()
                }
            }
        }
    }
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    var isAccessibilityEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    // Bumped on resume so the "used today" lines re-read the usage written by the service.
    var refreshTick by remember { mutableIntStateOf(0) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isAccessibilityEnabled = isAccessibilityServiceEnabled(context)
                refreshTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Whitelister", style = MaterialTheme.typography.headlineMedium)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (isAccessibilityEnabled) "Service Active" else "Service Disabled",
                    style = MaterialTheme.typography.titleMedium,
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

        FeatureCard(
            surface = IgSurface.REELS,
            title = "Remove Reels",
            description = "Blocks scrolling between reels",
            serviceEnabled = isAccessibilityEnabled,
            refreshTick = refreshTick
        )
        FeatureCard(
            surface = IgSurface.FEED,
            title = "Block Feed",
            description = "Scrolls the Home feed back to the top, without reloading it",
            serviceEnabled = isAccessibilityEnabled,
            refreshTick = refreshTick
        )
        FeatureCard(
            surface = IgSurface.EXPLORE,
            title = "Block Explore",
            description = "Scrolls the Explore suggestions back to the top. Search keeps working",
            serviceEnabled = isAccessibilityEnabled,
            refreshTick = refreshTick
        )
    }
}

/**
 * One blockable surface: on/off switch, the daily free time before the block engages, and how
 * much of it has been used today.
 */
@Composable
private fun FeatureCard(
    surface: IgSurface,
    title: String,
    description: String,
    serviceEnabled: Boolean,
    refreshTick: Int
) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(PreferencesManager.isEnabled(context, surface)) }
    var freeMinutes by remember { mutableIntStateOf(PreferencesManager.getFreeMinutes(context, surface)) }
    val usedMinutes = remember(refreshTick) {
        (PreferencesManager.getUsedMsToday(context, surface) / 60_000L).toInt()
    }
    var menuOpen by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    Text(description, style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { value ->
                        enabled = value
                        when (surface) {
                            IgSurface.REELS -> PreferencesManager.setReelsBlockingEnabled(context, value)
                            IgSurface.FEED -> PreferencesManager.setBlockFeedEnabled(context, value)
                            IgSurface.EXPLORE -> PreferencesManager.setBlockExploreEnabled(context, value)
                        }
                    },
                    enabled = serviceEnabled
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Free time per day", style = MaterialTheme.typography.bodyMedium)
                Box {
                    TextButton(onClick = { menuOpen = true }, enabled = serviceEnabled) {
                        Text(freeMinutesLabel(freeMinutes))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        PreferencesManager.FREE_MINUTES_OPTIONS.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(freeMinutesLabel(option)) },
                                onClick = {
                                    freeMinutes = option
                                    PreferencesManager.setFreeMinutes(context, surface, option)
                                    menuOpen = false
                                }
                            )
                        }
                    }
                }
            }
            Text(
                text = usageLine(enabled, freeMinutes, usedMinutes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun freeMinutesLabel(minutes: Int): String =
    if (minutes == 0) "None (always block)" else "$minutes min"

private fun usageLine(enabled: Boolean, freeMinutes: Int, usedMinutes: Int): String = when {
    !enabled -> "Used today: $usedMinutes min"
    freeMinutes == 0 -> "Used today: $usedMinutes min · blocking always"
    usedMinutes >= freeMinutes -> "Used today: $usedMinutes of $freeMinutes min · blocking now"
    else -> "Used today: $usedMinutes of $freeMinutes min · blocking after ${freeMinutes - usedMinutes} more min"
}

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val service = ComponentName(context, WhitelistAccessibilityService::class.java)
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val tasks = TextUtils.SimpleStringSplitter(':')
    tasks.setString(enabledServices)
    while (tasks.hasNext()) {
        if (ComponentName.unflattenFromString(tasks.next()) == service) return true
    }
    return false
}
