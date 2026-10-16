package com.xnotes.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Fullscreen launch screen: the angular "n" mark ([NLogo], drawn from the launcher-icon path) that
 * draws itself on in a loop over the "n-notes" wordmark, on the flat black stage that matches the
 * system splash background. Shown while the session restores, then faded out.
 */
@Composable
fun XnotesLoader(modifier: Modifier = Modifier) {
    val cfg = LocalConfiguration.current
    // Scale with the short edge but cap it, so phones render big without tablets ballooning.
    val shortEdge = minOf(cfg.screenWidthDp, cfg.screenHeightDp)
    val side = minOf(shortEdge * 0.34f, 180f).dp

    val transition = rememberInfiniteTransition(label = "loader")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "reveal",
    )

    Column(
        modifier.fillMaxSize().background(Color.Black),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        NLogo(Modifier.size(side), color = Color.White, progress = progress)
        Spacer(Modifier.height(18.dp))
        androidx.compose.material3.Text(
            "n-notes",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
        )
    }
}
