package com.xnotes.ui

import android.graphics.drawable.AnimationDrawable
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.xnotes.R

/**
 * Fullscreen launch animation: the exported frame-by-frame glitch loader
 * ([R.drawable.xnotes_loader] — a 24-frame ~1.6s [AnimationDrawable]) centred on a
 * pure-black stage that matches the frames' flat black background exactly, so
 * there's no edge seam. Shown while the session restores, then faded out.
 */
@Composable
fun XnotesLoader(modifier: Modifier = Modifier) {
    val cfg = LocalConfiguration.current
    val side = (minOf(cfg.screenWidthDp, cfg.screenHeightDp) * 0.35f).dp
    Box(
        modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { ctx -> ImageView(ctx).apply { setImageResource(R.drawable.xnotes_loader) } },
            update = { iv -> (iv.drawable as? AnimationDrawable)?.let { if (!it.isRunning) it.start() } },
            modifier = Modifier.size(side),
        )
    }
}
