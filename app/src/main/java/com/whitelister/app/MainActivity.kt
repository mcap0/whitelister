package com.whitelister.app

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.roundToInt
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
                    AppRoot()
                }
            }
        }
    }
}

/**
 * Google Play requires a prominent in-app disclosure and affirmative consent before an app that
 * is not an accessibility tool uses the AccessibilityService API, plus an in-app privacy policy.
 * The disclosure is shown until accepted (the service reads nothing before that, see
 * WhitelistAccessibilityService.onAccessibilityEvent); Decline, Back or Home never count as
 * consent and the screen comes back on the next launch. The policy stays reachable from the main
 * screen (info icon and footer link).
 */
@Composable
fun AppRoot() {
    val context = LocalContext.current
    var consented by remember { mutableStateOf(PreferencesManager.hasConsent(context)) }
    var showPolicy by rememberSaveable { mutableStateOf(false) }
    when {
        showPolicy -> PolicyScreen(onClose = { showPolicy = false })
        !consented -> ConsentScreen(
            onAccept = {
                PreferencesManager.setConsentGiven(context)
                consented = true
            },
            onDecline = { (context as? Activity)?.finish() },
            onViewPolicy = { showPolicy = true }
        )
        else -> MainScreen(onOpenPolicy = { showPolicy = true })
    }
}

@Composable
private fun ConsentScreen(onAccept: () -> Unit, onDecline: () -> Unit, onViewPolicy: () -> Unit) {
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
        Text(
            stringResource(R.string.consent_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            stringResource(R.string.consent_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        WlCard {
            DisclosureSection(R.string.consent_reads_title, R.string.consent_reads_body)
            Spacer(Modifier.height(14.dp))
            DisclosureSection(R.string.consent_why_title, R.string.consent_why_body)
            Spacer(Modifier.height(14.dp))
            DisclosureSection(R.string.consent_data_title, R.string.consent_data_body)
        }
        TextButton(onClick = onViewPolicy) {
            Text(stringResource(R.string.view_privacy_policy))
        }
        Text(
            stringResource(R.string.consent_ask),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = onAccept,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(stringResource(R.string.consent_accept))
        }
        OutlinedButton(
            onClick = onDecline,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(stringResource(R.string.consent_decline))
        }
    }
}

@Composable
private fun DisclosureSection(title: Int, body: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun PolicyScreen(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.privacy_policy_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onClose) {
                Text(stringResource(R.string.privacy_policy_close))
            }
        }
        WlCard {
            Text(stringResource(R.string.privacy_policy_text), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun MainScreen(onOpenPolicy: () -> Unit) {
    val context = LocalContext.current
    var isAccessibilityEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    // Bumped on resume so the usage lines re-read the usage written by the service.
    var refreshTick by remember { mutableIntStateOf(0) }
    var windowHours by remember { mutableIntStateOf(PreferencesManager.getWindowHours(context)) }

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
        Header(onInfo = onOpenPolicy)
        Spacer(Modifier.height(8.dp))
        ServiceStatus(isAccessibilityEnabled) {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        WindowCard(
            hours = windowHours,
            serviceEnabled = isAccessibilityEnabled,
            onChange = { h ->
                windowHours = h
                PreferencesManager.setWindowHours(context, h)
                refreshTick++
            }
        )
        SectionLabel("Instagram")
        FeatureCard(
            surface = IgSurface.REELS,
            title = "Remove Reels",
            description = "Watch the reel you opened, not the next one",
            serviceEnabled = isAccessibilityEnabled,
            refreshTick = refreshTick,
            windowHours = windowHours
        )
        FeatureCard(
            surface = IgSurface.FEED,
            title = "Block Feed",
            description = "The Home feed stays at the top, stories only",
            serviceEnabled = isAccessibilityEnabled,
            refreshTick = refreshTick,
            windowHours = windowHours
        )
        FeatureCard(
            surface = IgSurface.EXPLORE,
            title = "Block Explore",
            description = "No endless suggestions. Search still works",
            serviceEnabled = isAccessibilityEnabled,
            refreshTick = refreshTick,
            windowHours = windowHours
        )
        FavoritesTipCard()
        SectionLabel("Facebook")
        FeatureCard(
            surface = IgSurface.FB_REELS,
            title = "Remove Reels",
            description = "Watch the reel you opened, not the next one",
            serviceEnabled = isAccessibilityEnabled,
            refreshTick = refreshTick,
            windowHours = windowHours
        )
        FeatureCard(
            surface = IgSurface.FB_FEED,
            title = "Block Feed",
            description = "The Home feed stays at the top, stories only",
            serviceEnabled = isAccessibilityEnabled,
            refreshTick = refreshTick,
            windowHours = windowHours
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Everything runs on your device · v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        TextButton(onClick = onOpenPolicy, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.privacy_and_permissions), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun Header(onInfo: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(R.drawable.appicon),
            contentDescription = null,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
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
        if (onInfo != null) {
            IconButton(onClick = onInfo) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = stringResource(R.string.privacy_and_permissions),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
                    if (active) "Your settings apply to Instagram and Facebook" else "Turn on Whitelister in Accessibility settings",
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
 * Tip: Instagram's Favorites feed (only the accounts you pick, newest first), with a drawn
 * walkthrough ([FavoritesAnimation]) shown on request. Purely informational, no service logic.
 */
@Composable
private fun FavoritesTipCard() {
    var open by rememberSaveable { mutableStateOf(false) }
    WlCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Tip: see only who you choose", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text(
                    "Instagram has a Favorites feed with only the accounts you pick, newest first. Few people know it exists.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        TextButton(onClick = { open = !open }, modifier = Modifier.align(Alignment.End)) {
            Text(if (open) "Hide" else "Show me how")
        }
        AnimatedVisibility(visible = open) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                FavoritesAnimation()
                Spacer(Modifier.height(10.dp))
                Text(
                    "You can also open a profile, tap Following and choose Add to Favorites. " +
                        "Accounts are never told they were added.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
    refreshTick: Int,
    windowHours: Int
) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(PreferencesManager.isEnabled(context, surface)) }
    var freeMinutes by remember { mutableIntStateOf(PreferencesManager.getFreeMinutes(context, surface)) }
    val usedMinutes = remember(refreshTick, windowHours) {
        (PreferencesManager.getUsedMsToday(context, surface) / 60_000L).toInt()
    }
    val blockEndsAt = remember(refreshTick, windowHours, freeMinutes) {
        PreferencesManager.blockEndsAtMillis(context, surface)
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
                        IgSurface.FB_REELS -> PreferencesManager.setFbReelsBlockingEnabled(context, value)
                        IgSurface.FB_FEED -> PreferencesManager.setFbBlockFeedEnabled(context, value)
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
                    "Free time, then blocked for " + hoursLabel(windowHours),
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
                    text = usageLine(context, freeMinutes, usedMinutes, blockEndsAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun hoursLabel(hours: Int): String = if (hours == 1) "1 hour" else "$hours hours"

private fun usageLine(context: Context, freeMinutes: Int, usedMinutes: Int, blockEndsAt: Long): String =
    when {
        freeMinutes == 0 -> "Always blocked"
        blockEndsAt > 0 -> "Free time used up · blocked until " + formatEnd(context, blockEndsAt)
        else -> "$usedMinutes of $freeMinutes min used · blocks after ${freeMinutes - usedMinutes} more"
    }

/** "18:30", or "tomorrow 18:30" when the block ends on a later day. */
private fun formatEnd(context: Context, at: Long): String {
    val time = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(at))
    val end = java.util.Calendar.getInstance().apply { timeInMillis = at }
    val now = java.util.Calendar.getInstance()
    return if (end.get(java.util.Calendar.DAY_OF_YEAR) != now.get(java.util.Calendar.DAY_OF_YEAR)) "tomorrow $time" else time
}

/**
 * How long a block lasts once a feature's free time runs out: 1..24 hours (default 24), timed
 * from the moment the free time is used up; then the free time is full again.
 */
@Composable
private fun WindowCard(hours: Int, serviceEnabled: Boolean, onChange: (Int) -> Unit) {
    var sliderValue by remember(hours) { mutableFloatStateOf(hours.toFloat()) }
    val shown = sliderValue.roundToInt()
    WlCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Block duration", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text(
                    "How long a block lasts once the free time runs out",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                hoursLabel(shown),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(6.dp))
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onChange(sliderValue.roundToInt()) },
            valueRange = PreferencesManager.MIN_WINDOW_HOURS.toFloat()..PreferencesManager.MAX_WINDOW_HOURS.toFloat(),
            steps = PreferencesManager.MAX_WINDOW_HOURS - PreferencesManager.MIN_WINDOW_HOURS - 1,
            enabled = serviceEnabled
        )
        Text(
            text = windowDetail(shown),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun windowDetail(hours: Int): String =
    "The timer starts when the free time is used up. After ${hoursLabel(hours)} the free time is back"

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
