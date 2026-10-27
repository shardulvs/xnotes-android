package com.xnotes.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.xnotes.R
import com.xnotes.core.audio.AudioPill
import com.xnotes.core.audio.AudioTime
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/** A play triangle, or two pause bars while [playing], filled in [color]. */
@Composable
internal fun PlayGlyph(playing: Boolean, color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        if (playing) {
            drawRect(color, Offset(w * 0.18f, h * 0.12f), Size(w * 0.22f, h * 0.76f))
            drawRect(color, Offset(w * 0.6f, h * 0.12f), Size(w * 0.22f, h * 0.76f))
        } else {
            val p = Path().apply {
                moveTo(w * 0.2f, h * 0.08f)
                lineTo(w * 0.92f, h * 0.5f)
                lineTo(w * 0.2f, h * 0.92f)
                close()
            }
            drawPath(p, color)
        }
    }
}

/**
 * Beside Insert while a recording's player is open in this pane: where playback stands, and a tap
 * opens the player. Down a side rail the time stacks, minutes over seconds.
 */
@Composable
internal fun PlaybackChip(session: AudioPlayback.Session) {
    val palette = LocalPalette.current
    val bar = LocalBar.current
    var open by remember { mutableStateOf(false) }
    val time = AudioTime.format(session.position, session.duration >= 3_600_000)
    val fg = palette.selectionForeground.toComposeColor()
    val label = stringResource(R.string.audio_chip, time)
    Box(Modifier.chipAir(bar.vertical)) {
        val shape = RoundedCornerShape(bar.circle / 2)
        val base = Modifier
            .clip(shape)
            .background(palette.selectionBackground.toComposeColor())
            .clickable { open = !open }
            .semantics { contentDescription = label }
        if (bar.vertical) {
            Column(
                base.width(bar.circle).padding(vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                PlayGlyph(session.playing, fg, 11.dp)
                for (part in time.split(':')) Text(part, color = fg, fontSize = bar.label, fontWeight = FontWeight.Medium)
            }
        } else {
            Row(
                base.height(bar.circle).padding(start = 10.dp, end = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                PlayGlyph(session.playing, fg, 12.dp)
                Text(time, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
        if (open) PlayerPanel(session) { open = false }
    }
}

/**
 * Beside Insert while this pane records: a red chip with the recorded time and a beating dot,
 * calmer when paused. A tap opens Pause or Resume, Stop and place, and Discard. Down a side rail
 * the time stacks, as the player's does.
 */
@Composable
internal fun RecordingChip(session: AudioRecording.Session) {
    val palette = LocalPalette.current
    val m = palette.materialColors
    val bar = LocalBar.current
    var open by remember { mutableStateOf(false) }
    val time = AudioTime.format(session.seconds * 1000)
    val label = stringResource(R.string.recording_chip, time)
    val fg = (if (session.paused) m.error else m.onErrorContainer).toComposeColor()
    val shape = RoundedCornerShape(bar.circle / 2)
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "rec")
    val dotAlpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween<Float>(600),
            androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "dot",
    )
    Box(Modifier.chipAir(bar.vertical)) {
        val base = Modifier
            .clip(shape)
            .then(
                if (session.paused) Modifier.border(1.dp, m.error.toComposeColor(), shape)
                else Modifier.background(m.errorContainer.toComposeColor()),
            )
            .clickable { open = !open }
            .semantics { contentDescription = label }
        val marker: @Composable () -> Unit = {
            if (session.paused) {
                PlayGlyph(true, fg, 11.dp)
            } else {
                Box(Modifier.size(10.dp).clip(CircleShape).background(REC_RED.copy(alpha = dotAlpha)))
            }
        }
        if (bar.vertical) {
            Column(
                base.width(bar.circle).padding(vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                marker()
                for (part in time.split(':')) Text(part, color = fg, fontSize = bar.label, fontWeight = FontWeight.Medium)
            }
        } else {
            Row(
                base.height(bar.circle).padding(start = 11.dp, end = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                marker()
                Text(time, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
        if (open) {
            DropdownMenu(expanded = true, onDismissRequest = { open = false }) {
                Row(
                    Modifier.padding(start = 16.dp, end = 18.dp, top = 6.dp, bottom = 6.dp).widthIn(min = 200.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(if (session.paused) R.string.recording_paused else R.string.recording),
                        color = palette.textDim.toComposeColor(),
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    LevelMeter(session, m.error.toComposeColor(), palette.border.toComposeColor())
                }
                if (session.paused) {
                    InsertMenuRow(XnotesIcons.mic, stringResource(R.string.recording_resume)) { open = false; AudioRecording.resume() }
                } else {
                    InsertMenuRow(XnotesIcons.pause, stringResource(R.string.recording_pause)) { open = false; AudioRecording.pause() }
                }
                InsertMenuRow(XnotesIcons.stop, stringResource(R.string.recording_stop_place)) { open = false; AudioRecording.stop() }
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = palette.border.toComposeColor())
                InsertMenuRow(XnotesIcons.trash, stringResource(R.string.recording_discard), color = palette.danger.toComposeColor()) {
                    open = false
                    AudioRecording.discard()
                }
            }
        }
    }
}

/** Air along the bar, so a chip sits as far from its neighbours as two buttons sit apart. */
private fun Modifier.chipAir(vertical: Boolean) = padding(
    horizontal = if (vertical) 0.dp else 4.dp,
    vertical = if (vertical) 4.dp else 0.dp,
)

/** The recording dot's red, the colour every recorder uses for it, whatever the theme. */
private val REC_RED = Color(0xFFFF5449)

/** The microphone's level just now, as a row of bars that runs while the menu is open; flat while paused. */
@Composable
private fun LevelMeter(session: AudioRecording.Session, on: Color, off: Color) {
    val history = remember { androidx.compose.runtime.mutableStateListOf<Float>().apply { repeat(12) { add(0f) } } }
    androidx.compose.runtime.LaunchedEffect(session) {
        while (true) {
            history.removeAt(0)
            history.add(if (session.paused) 0f else session.level)
            kotlinx.coroutines.delay(90)
        }
    }
    Canvas(Modifier.width(58.dp).height(16.dp)) {
        val barW = 3.dp.toPx()
        val step = size.width / history.size
        for ((i, l) in history.withIndex()) {
            val h = maxOf(barW, kotlin.math.sqrt(l) * size.height)
            drawRoundRect(
                color = if (session.paused) off else on,
                topLeft = Offset(i * step, (size.height - h) / 2f),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f),
            )
        }
    }
}

/** The player under the chip: the waveform to scrub, the time, play and the ten-second skips, speed, close. */
@Composable
private fun PlayerPanel(session: AudioPlayback.Session, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    val accent = palette.accent.toComposeColor()
    val dim = palette.textDim.toComposeColor()
    val long = session.duration >= 3_600_000
    // Not focusable, so a tap on the page passes through: on ink it seeks, elsewhere it closes this.
    DropdownMenu(expanded = true, onDismissRequest = onDismiss, properties = PopupProperties(focusable = false)) {
        Column(Modifier.width(360.dp).padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(XnotesIcons.mic, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                Text(
                    session.item.name,
                    color = palette.text.toComposeColor(),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Scrubber(session, accent, palette.border.toComposeColor(), Modifier.padding(top = 10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(AudioTime.format(session.position, long), color = dim, fontSize = 12.sp)
                Text(AudioTime.format(session.duration, long), color = dim, fontSize = 12.sp)
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkipButton(XnotesIcons.skipBack, stringResource(R.string.audio_back)) { AudioPlayback.skip(-10_000) }
                val playLabel = stringResource(if (session.playing) R.string.audio_pause else R.string.audio_play)
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(accent)
                        .clickable { AudioPlayback.toggle() }
                        .semantics { contentDescription = playLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    PlayGlyph(session.playing, palette.onAccent.toComposeColor(), 20.dp)
                }
                SkipButton(XnotesIcons.skipForward, stringResource(R.string.audio_forward)) { AudioPlayback.skip(10_000) }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp), color = palette.border.toComposeColor())
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                val followLabel = stringResource(R.string.audio_follow)
                androidx.compose.material3.Switch(
                    checked = AudioPlayback.follow,
                    onCheckedChange = { AudioPlayback.follow = it },
                    modifier = Modifier.semantics { contentDescription = followLabel },
                )
                Text(followLabel, color = palette.text.toComposeColor(), fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp))
                Spacer(Modifier.weight(1f))
                val speedLabel = stringResource(R.string.audio_speed)
                Box(
                    Modifier
                        .heightIn(min = 32.dp)
                        .widthIn(min = 60.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, palette.border.toComposeColor(), RoundedCornerShape(8.dp))
                        .clickable { AudioPlayback.cycleSpeed() }
                        .semantics { contentDescription = speedLabel }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(speedText(AudioPlayback.speed), color = palette.text.toComposeColor(), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Text(
                    stringResource(R.string.audio_close),
                    color = accent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .clickable { onDismiss(); AudioPlayback.close() }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                )
            }
        }
    }
}

private fun speedText(s: Float): String = (if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString()) + "×"

@Composable
private fun SkipButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val dim = LocalPalette.current.textDim.toComposeColor()
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = dim, modifier = Modifier.size(26.dp))
        Text("10", color = dim, fontSize = 9.sp, fontWeight = FontWeight.Medium)
    }
}

/** The waveform as a scrubber: played bars in the accent; a tap or a drag along it moves playback there. */
@Composable
private fun Scrubber(session: AudioPlayback.Session, played: Color, rest: Color, modifier: Modifier = Modifier) {
    val peaks = session.item.audio.peaks
    val duration = session.duration.coerceAtLeast(1)
    val label = stringResource(R.string.audio_seek)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .semantics { contentDescription = label }
            .pointerInput(session) {
                detectTapGestures { o -> AudioPlayback.seek((o.x / size.width * duration).toInt()) }
            }
            .pointerInput(session) {
                detectHorizontalDragGestures(
                    onDragEnd = { AudioPlayback.seek(session.position) },
                ) { change, _ -> AudioPlayback.seek((change.position.x / size.width * duration).toInt(), exact = false) }
            },
    ) {
        val barW = 3.dp.toPx()
        val gap = 2.dp.toPx()
        val n = ((size.width + gap) / (barW + gap)).toInt().coerceAtLeast(1)
        val step = (size.width + gap) / n
        val playedN = (n.toLong() * session.position / duration).toInt()
        for (i in 0 until n) {
            val level = if (peaks.isEmpty()) 0f else AudioPill.peakAt(peaks, i, n) / 255f
            val h = maxOf(barW, level * size.height)
            drawRoundRect(
                color = if (i < playedN) played else rest,
                topLeft = Offset(i * step, (size.height - h) / 2f),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f),
            )
        }
    }
}
