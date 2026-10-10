package com.xnotes.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.delay

/**
 * A canvas that can flash brief action feedback ("Deleted", "Copied", ...). Both the
 * paged [Editor] and the infinite editor implement it so one pill composable serves both.
 * [toastToken] bumps on every message so re-showing the same text still re-arms the pill.
 */
interface ToastHost {
    val toastText: String?
    val toastToken: Int
}

/**
 * A small themed pill at the bottom-centre of the canvas, mirroring the flat surface + 1dp
 * border chrome the app uses everywhere (see ZoomLockHint). Auto-dismisses after a short beat.
 */
@Composable
fun BoxScope.CanvasToastPill(host: ToastHost) {
    val palette = LocalPalette.current
    var visible by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf<String?>(null) }
    var armToken by remember { mutableStateOf(0) }
    LaunchedEffect(host.toastToken) {
        if (host.toastToken > 0 && !host.toastText.isNullOrEmpty()) {
            shown = host.toastText
            visible = true
            armToken++
        }
    }
    LaunchedEffect(armToken) {
        if (armToken > 0) {
            delay(1500)
            visible = false
        }
    }
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Text(
            text = shown ?: "",
            color = palette.text.toComposeColor(),
            fontSize = 13.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(palette.menuBg.toComposeColor())
                .border(1.dp, palette.border.toComposeColor(), RoundedCornerShape(50))
                .padding(horizontal = 18.dp, vertical = 9.dp),
        )
    }
}
