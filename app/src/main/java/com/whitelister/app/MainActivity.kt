package com.whitelister.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.whitelister.app.ui.theme.WhitelisterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
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
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Header()
        Spacer(Modifier.height(8.dp))
        ServiceStatus(isAccessibilityEnabled) {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        SectionLabel("Instagram")
        FeatureCard(
            surface = IgSurface.REELS,
            title = "Remove Reels",
            description = "Watch the reel you opened, not the next one",
            serviceEnabled = isAccessibilityEnabled,
            refreshTick = refreshTick
        )
        FeatureCard(
            surface = IgSurface.FEED,
            title = "Block Feed",
            description = "The Home feed stays at the top, stories only",
            serviceEnabled = isAccessibilityEnabled,
            refreshTick = refreshTick
        )
        FeatureCard(
            surface = IgSurface.EXPLORE,
            title = "Block Explore",
            description = "No endless suggestions. Search still works",
            serviceEnabled = isAccessibilityEnabled,
            refreshTick = refreshTick
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Everything runs on your device · v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(R.drawable.appicon),
            contentDescription = null,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                "Whitelister",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Take back control of your feed",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp)
    )
}

@Composable
private fun WlCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(18.dp), content = content)
    }
}

@Composable
private fun ServiceStatus(active: Boolean, onEnable: () -> Unit) {
    WlCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (active) "Service active" else "Service off",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    if (active) "Your settings apply to Instagram" else "Turn on Whitelister in Accessibility settings",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!active) {
                Spacer(Modifier.width(8.dp))
                Button(onClick = onEnable, shape = RoundedCornerShape(12.dp)) {
                    Text("Enable")
                }
            }
        }
    }
}

/**
 * One blockable surface: on/off switch and, while on, the daily free time before the block
 * engages plus how much of it has been used today.
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

    WlCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
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
        AnimatedVisibility(visible = enabled) {
            Column {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Free time per day",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PreferencesManager.FREE_MINUTES_OPTIONS.forEach { option ->
                        FilterChip(
                            selected = freeMinutes == option,
                            onClick = {
                                freeMinutes = option
                                PreferencesManager.setFreeMinutes(context, surface, option)
                            },
                            label = { Text(if (option == 0) "None" else "$option min") },
                            enabled = serviceEnabled,
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                }
                if (freeMinutes > 0) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { (usedMinutes.toFloat() / freeMinutes).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape),
                        trackColor = MaterialTheme.colorScheme.outline,
                        drawStopIndicator = {}
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = usageLine(freeMinutes, usedMinutes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun usageLine(freeMinutes: Int, usedMinutes: Int): String = when {
    freeMinutes == 0 -> "Always blocked · $usedMinutes min today"
    usedMinutes >= freeMinutes -> "Free time used up · blocked for today"
    else -> "$usedMinutes of $freeMinutes min used · blocks after ${freeMinutes - usedMinutes} more"
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
