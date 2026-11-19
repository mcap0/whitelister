package com.whitelister.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/**
 * A looping, drawn walkthrough of Instagram's Favorites feed (tap the feed title, pick Favorites,
 * add accounts, see only them). It is a generic mock — no screenshots of Instagram and no real
 * accounts — so it stays tiny and contains no third-party content.
 */

/** The step captions shown under the animation, one per [Phase]. */
val FAVORITES_STEPS = listOf(
    "On the Home feed, tap the title at the top",
    "Choose Favorites",
    "Add the accounts you care about (up to 50)",
    "The feed now shows only them, newest first"
)

private enum class Phase { FEED, MENU, ADD, DONE }

// Phone screen size and the finger targets, in dp.
private const val W = 220f
private const val H = 400f
private val TITLE = Offset(50f, 24f)
private val FAV_ROW = Offset(62f, 91f)
private const val SHEET_TOP = 150f
private val ADD_1 = Offset(184f, SHEET_TOP + 70f)
private val ADD_2 = Offset(184f, SHEET_TOP + 114f)
private val DONE_BTN = Offset(192f, SHEET_TOP + 22f)

private data class Account(val name: String, val color: Color)

private val ACCOUNTS = listOf(
    Account("anna", Color(0xFFE57373)),
    Account("luca", Color(0xFF64B5F6)),
    Account("daily_memes", Color(0xFFFFB74D)),
    Account("viral_clips", Color(0xFF9575CD))
)

@Composable
fun FavoritesAnimation(modifier: Modifier = Modifier) {
    var phase by remember { mutableStateOf(Phase.FEED) }
    var added by remember { mutableIntStateOf(0) }
    val finger = remember { Animatable(Offset(150f, 320f), Offset.VectorConverter) }
    val press = remember { Animatable(0f) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Runs only while the screen is in front: restarts from the first step on resume.
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        suspend fun tap() {
            press.animateTo(1f, tween(110))
            press.animateTo(0f, tween(220))
        }
        while (true) {
            phase = Phase.FEED
            added = 0
            finger.snapTo(Offset(150f, 330f))
            delay(1200)
            finger.animateTo(TITLE, tween(800))
            tap()
            phase = Phase.MENU
            delay(900)
            finger.animateTo(FAV_ROW, tween(500))
            tap()
            phase = Phase.ADD
            delay(1000)
            finger.animateTo(ADD_1, tween(600))
            tap()
            added = 1
            delay(500)
            finger.animateTo(ADD_2, tween(450))
            tap()
            added = 2
            delay(900)
            finger.animateTo(DONE_BTN, tween(500))
            tap()
            phase = Phase.DONE
            finger.animateTo(Offset(150f, 330f), tween(700))
            delay(3000)
        }
    } }

    val step = phase.ordinal
    Column(
        modifier = modifier.semantics {
            contentDescription = "Animation: " + FAVORITES_STEPS.joinToString(". ")
        },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        PhoneFrame {
            Screen(phase, added)
            // The finger.
            Box(
                Modifier
                    .offset((finger.value.x - 14f).dp, (finger.value.y - 14f).dp)
                    .size(28.dp)
                    .scale(1f - 0.25f * press.value)
                    .clip(CircleShape)
                    .background(Color(0xFF202020).copy(alpha = 0.30f + 0.25f * press.value))
                    .border(2.dp, Color.White.copy(alpha = 0.8f), CircleShape)
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "${step + 1}. ${FAVORITES_STEPS[step]}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun PhoneFrame(content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier
            .size((W + 12).dp, (H + 12).dp)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f))
            .padding(6.dp)
    ) {
        Box(
            Modifier
                .size(W.dp, H.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surface),
            content = content
        )
    }
}

@Composable
private fun Screen(phase: Phase, added: Int) {
    val faint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val favorites = phase == Phase.DONE

    // Top bar: the feed title with its dropdown arrow.
    Row(
        Modifier.offset(12.dp, 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (favorites) "Favorites" else "For you",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (favorites) {
            Spacer(Modifier.width(3.dp))
            Icon(Icons.Filled.Star, null, Modifier.size(14.dp), tint = Color(0xFF2E7D32))
        }
        Icon(
            Icons.Filled.KeyboardArrowDown, null, Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurface
        )
    }

    if (favorites) {
        Post(y = 44f, account = ACCOUNTS[0], star = true, faint = faint)
        Post(y = 220f, account = ACCOUNTS[1], star = true, faint = faint)
    } else {
        // Stories row.
        Row(Modifier.offset(12.dp, 44.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ACCOUNTS.forEach { a ->
                Box(
                    Modifier
                        .size(38.dp)
                        .border(2.dp, a.color, CircleShape)
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(faint)
                )
            }
        }
        Post(y = 96f, account = ACCOUNTS[2], star = false, faint = faint)
        Post(y = 272f, account = ACCOUNTS[3], star = false, faint = faint)
    }

    if (phase == Phase.MENU) {
        Card(
            modifier = Modifier
                .offset(12.dp, 40.dp)
                .width(150.dp),
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            MenuRow("Following", star = false)
            MenuRow("Favorites", star = true)
        }
    }

    if (phase == Phase.ADD) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
        )
        Column(
            Modifier
                .offset(0.dp, SHEET_TOP.dp)
                .size(W.dp, (H - SHEET_TOP).dp)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp)
        ) {
            Row(Modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Add favorites",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text("Done", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            ACCOUNTS.forEachIndexed { i, a ->
                Row(Modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(a.color)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        a.name, fontSize = 12.sp, modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    val on = i < added
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (on) Color.Transparent else MaterialTheme.colorScheme.primary,
                        border = if (on) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null
                    ) {
                        Text(
                            if (on) "Added" else "Add",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .width(44.dp)
                                .padding(vertical = 5.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(label: String, star: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(34.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label, fontSize = 13.sp, modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface
        )
        if (star) Icon(Icons.Filled.Star, null, Modifier.size(16.dp), tint = Color(0xFF2E7D32))
    }
}

@Composable
private fun Post(y: Float, account: Account, star: Boolean, faint: Color) {
    Column(Modifier.offset(0.dp, y.dp).width(W.dp)) {
        Row(
            Modifier
                .height(30.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(account.color)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                account.name, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (star) {
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Filled.Star, null, Modifier.size(12.dp), tint = Color(0xFF2E7D32))
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .background(account.color.copy(alpha = 0.35f))
        )
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(3) { Box(Modifier.size(12.dp).clip(CircleShape).background(faint)) }
        }
    }
}
