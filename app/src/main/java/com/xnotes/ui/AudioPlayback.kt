package com.xnotes.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.xnotes.core.model.AudioItem
import com.xnotes.platform.AudioPositions

/** What the player needs from the editor whose recording it plays. */
interface AudioOwner {
    /** Draw [item] again: its playhead moved, or the player opened or closed on it. */
    fun audioRepaint(item: AudioItem)

    /** Whether [item] is still in this editor's document. */
    fun holdsAudio(item: AudioItem): Boolean

    /** The key [AudioPositions] keeps recording [rid] of this editor's document under, or null when it has none. */
    fun audioPositionKey(rid: Int): String?

    /** The recording could not be played. */
    fun audioFailed()

    /** Nothing plays while something records, since it would be recorded too. */
    fun audioBusy()

    /**
     * Playback of recording [rid] moved from [from] to [to] ms; [rid] is null once the player has
     * closed. The ink tied to it fades ahead of the playhead, and the view may follow it.
     */
    fun audioPlayhead(rid: Int?, from: Int, to: Int)
}

/**
 * The one player in the app: one recording, for one editor, at a time. The toolbar chip and its
 * panel read [session]; the pill reads its own [AudioItem.playhead], which the player keeps moving.
 * It never plays off screen: the activity pauses it when it stops.
 */
object AudioPlayback {

    class Session(val owner: AudioOwner, val item: AudioItem) {
        var position by mutableIntStateOf(0)
            internal set
        var playing by mutableStateOf(false)
            internal set
        val duration: Int get() = item.audio.durationMs
    }

    var session by mutableStateOf<Session?>(null)
        private set

    /** Whether the view follows the ink being played; a scroll by hand turns it off, the panel back on. */
    var follow by mutableStateOf(true)

    /** The playback speed, kept from one recording to the next. */
    var speed by mutableFloatStateOf(1f)
        private set

    val SPEEDS = listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 0.75f)

    private const val TICK_MS = 100L

    private var app: Context? = null
    private var player: MediaPlayer? = null
    private var prepared = false
    private var wantPlay = false
    private var lastSecond = -1
    private var lastStep = -1
    private val main = Handler(Looper.getMainLooper())
    private var focus: AudioFocusRequest? = null
    private var noisyOn = false

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** Headphones pulled out: stop talking out loud. */
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = pause()
    }

    private val tick = object : Runnable {
        override fun run() {
            onTick(force = false)
            if (session?.playing == true) main.postDelayed(this, TICK_MS)
        }
    }

    fun init(context: Context) {
        app = context.applicationContext
        AudioPositions.init(context)
    }

    /** Open the player on [item] for [owner], closing any other, at [from] or where it was left; [play] starts it. */
    fun open(owner: AudioOwner, item: AudioItem, from: Int? = null, play: Boolean = true) {
        if (AudioRecording.session != null) {
            owner.audioBusy()
            return
        }
        val current = session
        if (current != null && current.item === item && current.owner === owner) {
            if (from != null) seek(from)
            if (play) play() else pause()
            return
        }
        close()
        val ctx = app ?: return
        val remembered = owner.audioPositionKey(item.rid)?.let { AudioPositions.get(it) } ?: 0
        val start = (from ?: remembered).coerceIn(0, item.audio.durationMs.coerceAtLeast(0))
        val s = Session(owner, item).also { it.position = start }
        session = s
        follow = true
        item.playhead = start
        item.playing = false
        lastSecond = -1
        lastStep = -1
        owner.audioRepaint(item)
        owner.audioPlayhead(item.rid, -1, start)
        val mp = MediaPlayer()
        player = mp
        prepared = false
        wantPlay = play
        try {
            mp.setAudioAttributes(attributes)
            mp.setDataSource(ctx, android.net.Uri.fromFile(item.audio.file))
            mp.setOnPreparedListener { onPrepared(it) }
            mp.setOnCompletionListener { if (it === player) onCompleted() }
            mp.setOnErrorListener { p, _, _ ->
                if (p === player) fail()
                true
            }
            mp.prepareAsync()
        } catch (_: Exception) {
            fail()
        }
    }

    private fun onPrepared(mp: MediaPlayer) {
        if (mp !== player) return
        prepared = true
        val s = session ?: return
        if (s.position > 0) mp.seekTo(s.position.toLong(), MediaPlayer.SEEK_CLOSEST)
        if (wantPlay) play()
    }

    fun play() {
        val s = session ?: return
        val mp = player ?: return
        wantPlay = true
        if (!prepared || s.playing) return
        if (s.position >= s.duration - 50) seek(0)
        if (!requestFocus()) return
        mp.start()
        // Set after start: on some versions setting a speed while paused starts playback by itself.
        runCatching { mp.playbackParams = mp.playbackParams.setSpeed(speed) }
        s.playing = true
        s.item.playing = true
        listenForNoisy(true)
        main.removeCallbacks(tick)
        main.post(tick)
        s.owner.audioRepaint(s.item)
    }

    fun pause() {
        val s = session ?: return
        wantPlay = false
        if (!s.playing) return
        if (prepared) runCatching { player?.pause() }
        s.playing = false
        s.item.playing = false
        main.removeCallbacks(tick)
        onTick(force = true)
        remember(s)
        abandonFocus()
        listenForNoisy(false)
    }

    fun toggle() {
        if (session?.playing == true) pause() else play()
    }

    /** Jump to [ms]; [exact] lands on it rather than on the nearest frame, which a drag along the scrubber can skip. */
    fun seek(ms: Int, exact: Boolean = true) {
        val s = session ?: return
        val to = ms.coerceIn(0, s.duration.coerceAtLeast(0))
        val from = s.position
        s.position = to
        s.item.playhead = to
        s.owner.audioPlayhead(s.item.rid, from, to)
        if (prepared) runCatching { player?.seekTo(to.toLong(), if (exact) MediaPlayer.SEEK_CLOSEST else MediaPlayer.SEEK_PREVIOUS_SYNC) }
        lastSecond = -1
        s.owner.audioRepaint(s.item)
    }

    fun skip(deltaMs: Int) {
        val s = session ?: return
        seek(s.position + deltaMs)
    }

    fun cycleSpeed() {
        speed = SPEEDS[(SPEEDS.indexOf(speed) + 1) % SPEEDS.size]
        val mp = player ?: return
        if (prepared && session?.playing == true) runCatching { mp.playbackParams = mp.playbackParams.setSpeed(speed) }
    }

    /** End the session: the pill shows its length again and where it stood is remembered. */
    fun close() {
        val s = session ?: return
        main.removeCallbacks(tick)
        player?.let { mp ->
            if (prepared && s.position < s.duration) runCatching { s.position = mp.currentPosition }
            runCatching { mp.release() }
        }
        player = null
        prepared = false
        wantPlay = false
        remember(s)
        s.playing = false
        s.item.playing = false
        s.item.playhead = -1
        session = null
        abandonFocus()
        listenForNoisy(false)
        s.owner.audioPlayhead(null, s.position, s.position)
        s.owner.audioRepaint(s.item)
    }

    /** A scroll or zoom by hand in [owner] while its recording plays: the view stays where it is put. */
    fun userMoved(owner: AudioOwner) {
        val s = session ?: return
        if (s.owner === owner && s.playing) follow = false
    }

    /** Play from a little before [ms], the moment ink was written: a finger tap on it during playback. */
    fun playFromMoment(ms: Int) {
        seek((ms - com.xnotes.core.audio.SyncIndex.LEAD_IN_MS).coerceAtLeast(0))
        play()
    }

    /** A finger tap at [p] on [item]: the waveform plays from where it is touched, anywhere else plays or pauses. */
    fun tap(owner: AudioOwner, item: AudioItem, p: com.xnotes.core.geometry.Pt) {
        val s = session
        val at = if (com.xnotes.core.audio.AudioPill.onDisc(item, p)) null else com.xnotes.core.audio.AudioPill.seekAt(item, p)
        when {
            at != null -> open(owner, item, from = at, play = true)
            s != null && s.item === item && s.owner === owner -> toggle()
            else -> open(owner, item)
        }
    }

    /** Close the player if it is [owner]'s, as when that editor's note closes. */
    fun closeFor(owner: AudioOwner) {
        if (session?.owner === owner) close()
    }

    /** [owner]'s document changed: once the edit settles, close the player if a delete, cut or undo took its pill. */
    fun closeIfGone(owner: AudioOwner) {
        val s = session?.takeIf { it.owner === owner } ?: return
        main.post { if (session === s && !owner.holdsAudio(s.item)) close() }
    }

    private fun onTick(force: Boolean) {
        val s = session ?: return
        if (!s.owner.holdsAudio(s.item)) {
            close()
            return
        }
        val mp = player ?: return
        if (!prepared) return
        val pos = runCatching { mp.currentPosition }.getOrDefault(s.position).coerceIn(0, s.duration.coerceAtLeast(0))
        val from = s.position
        s.position = pos
        s.item.playhead = pos
        if (pos != from) s.owner.audioPlayhead(s.item.rid, from, pos)
        // The pill redraws only when what it shows changes: a second on its clock or a sliver of its waveform.
        val second = pos / 1000
        val step = pos / (s.duration / 200).coerceAtLeast(250)
        if (force || second != lastSecond || step != lastStep) {
            lastSecond = second
            lastStep = step
            s.owner.audioRepaint(s.item)
        }
    }

    private fun onCompleted() {
        val s = session ?: return
        main.removeCallbacks(tick)
        wantPlay = false
        val from = s.position
        s.position = s.duration
        s.item.playhead = s.duration
        s.owner.audioPlayhead(s.item.rid, from, s.duration)
        s.playing = false
        s.item.playing = false
        abandonFocus()
        listenForNoisy(false)
        // Played to the end, so the next Play starts over.
        s.owner.audioPositionKey(s.item.rid)?.let { AudioPositions.put(it, 0) }
        s.owner.audioRepaint(s.item)
    }

    private fun fail() {
        val owner = session?.owner
        close()
        owner?.audioFailed()
    }

    private fun remember(s: Session) {
        val key = s.owner.audioPositionKey(s.item.rid) ?: return
        AudioPositions.put(key, if (s.position >= s.duration - 1000) 0 else s.position)
    }

    private fun requestFocus(): Boolean {
        val am = app?.getSystemService(AudioManager::class.java) ?: return true
        val req = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change -> if (change < 0) main.post { pause() } }
            .build()
            .also { focus = it }
        return am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        val req = focus ?: return
        app?.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(req)
    }

    private fun listenForNoisy(on: Boolean) {
        val ctx = app ?: return
        if (on == noisyOn) return
        noisyOn = on
        if (on) {
            ContextCompat.registerReceiver(ctx, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            runCatching { ctx.unregisterReceiver(noisy) }
        }
    }
}
