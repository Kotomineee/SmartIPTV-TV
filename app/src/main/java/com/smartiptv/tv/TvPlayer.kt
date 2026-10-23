package com.smartiptv.tv

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import java.util.Locale

@androidx.annotation.OptIn(UnstableApi::class)
class TvPlayer(
    context: Context, private val api: XtreamApi, private val db: LibraryDb, private val jobs: Jobs,
    initial: Content, restart: Boolean, private val queue: List<Content>, private val exit: () -> Unit,
    private val accountChanged: () -> Unit,
) : FrameLayout(context) {
    var item: Content = initial; private set
    var engine: ExoPlayer? = null; private set
    private var mediaSession: MediaSession? = null
    val video = LayoutInflater.from(context).inflate(R.layout.player_surface, this, false) as PlayerView
    val header = context.row().apply { pad(18, 10); setBackgroundColor(0xe8070b0a.toInt()) }
    val footer = context.column().apply { pad(18, 10); setBackgroundColor(0xe8070b0a.toInt()) }
    val title = context.text(item.displayName, 18f, bold = true).apply { singleLineEllipsis() }
    val timeline = SeekBar(context).apply { max = 10000; contentDescription = "Posição da reprodução"; progressTintList = ColorStateList.valueOf(Palette.green); thumbTintList = ColorStateList.valueOf(Palette.green) }
    val volume = SeekBar(context).apply { max = 100; progress = 70; keyProgressIncrement = 5; contentDescription = "Volume"; progressTintList = ColorStateList.valueOf(Palette.green); thumbTintList = ColorStateList.valueOf(Palette.green) }
    val time = context.text("00:00 / 00:00", 11f)
    private val status = context.text("Conectando à transmissão…", 13f, Palette.muted).apply { gravity = Gravity.CENTER }
    private val retry = context.button("Tentar novamente") { start(item, resumePosition, true) }
    private val messagePanel = context.column().apply { pad(20); gravity = Gravity.CENTER }
    val pause = iconButton("pause", "Pausar") { togglePause() }
    private val mute = iconButton("volume", "Silenciar") { volume.progress = if (volume.progress == 0) lastVolume else 0 }
    private val fullscreenButton = iconButton("full", "Alternar tela cheia") { fullScreen = !fullScreen; layoutVideo(); reveal() }
    private val handler = Handler(Looper.getMainLooper())
    private val hide = Runnable { hideControls() }
    private var lastSaved = 0L
    private var scrubbing = false
    private var menuOpen = false
    private var editingVolume = false
    private var errorVisible = false
    private var ended = false
    private var closed = false
    private var active = true
    private var resumePosition = 0L
    private var playOnResume = true
    private var lastVolume = 70
    var fullScreen = true; private set
    var controlsVisible: Boolean = true; private set
    private val tick = object : Runnable {
        override fun run() {
            if (closed) return
            updatePosition()
            if (System.currentTimeMillis() - lastSaved >= 5000) saveProgress()
            handler.postDelayed(this, 500)
        }
    }

    init {
        setBackgroundColor(Color.BLACK); isFocusable = true; isFocusableInTouchMode = true
        video.isFocusable = false
        addView(video, LayoutParams(-1, -1))
        header.addView(iconButton("back", when (initial.kind) { "episode" -> "Voltar aos episódios"; "movie" -> "Voltar ao filme"; else -> "Voltar ao catálogo" }, exit), LinearLayout.LayoutParams(context.dp(40), context.dp(40)))
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = context.dp(8) })
        addView(header, LayoutParams(-1, context.dp(60), Gravity.TOP))
        footer.addView(timeline, LinearLayout.LayoutParams(-1, context.dp(31)))
        val row = context.row()
        row.addView(pause, LinearLayout.LayoutParams(context.dp(40), context.dp(40)))
        row.addView(iconButton("rewind", "Voltar 10 segundos") { skip(-10000) }, LinearLayout.LayoutParams(context.dp(40), context.dp(40)))
        row.addView(iconButton("forward", "Avançar 10 segundos") { skip(10000) }, LinearLayout.LayoutParams(context.dp(40), context.dp(40)))
        row.addView(time, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = context.dp(8) })
        row.addView(mute, LinearLayout.LayoutParams(context.dp(36), context.dp(40)))
        row.addView(volume, LinearLayout.LayoutParams(context.dp(85), context.dp(38)))
        row.addView(iconButton("music", "Faixa de áudio") { trackMenu(C.TRACK_TYPE_AUDIO) }, LinearLayout.LayoutParams(context.dp(40), context.dp(40)))
        row.addView(iconButton("subtitle", "Legendas") { trackMenu(C.TRACK_TYPE_TEXT) }, LinearLayout.LayoutParams(context.dp(40), context.dp(40)))
        row.addView(fullscreenButton, LinearLayout.LayoutParams(context.dp(40), context.dp(40)))
        footer.addView(row); addView(footer, LayoutParams(-1, -2, Gravity.BOTTOM))
        messagePanel.addView(status); messagePanel.gap(13)
        messagePanel.addView(retry, LinearLayout.LayoutParams(-2, context.dp(41))); retry.visibility = GONE
        addView(messagePanel, LayoutParams(-1, -2, Gravity.CENTER))
        timeline.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) { if (fromUser) { reveal(); engine?.let { time.text = "${stamp(duration() * value / 10000)} / ${stamp(duration())}" } } }
            override fun onStartTrackingTouch(bar: SeekBar) { scrubbing = true; reveal() }
            override fun onStopTrackingTouch(bar: SeekBar) { scrubbing = false; seek(duration() * bar.progress / 10000); reveal() }
        })
        timeline.setOnKeyListener { _, code, event ->
            if (code in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT) && event.action == KeyEvent.ACTION_DOWN) {
                skip(if (code == KeyEvent.KEYCODE_DPAD_LEFT) -10000 else 10000); true
            } else false
        }
        volume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                engine?.volume = value / 100f
                if (value > 0) lastVolume = value
                mute.setCompoundDrawablesWithIntrinsicBounds(Glyph(if (value == 0) "muted" else "volume", Palette.text, context.dp(19)), null, null, null)
                mute.contentDescription = if (value == 0) "Ativar som" else "Silenciar"
                if (fromUser) reveal()
            }
            override fun onStartTrackingTouch(bar: SeekBar) { scrubbing = true; reveal() }
            override fun onStopTrackingTouch(bar: SeekBar) { scrubbing = false; reveal() }
        })
        volume.setOnKeyListener { _, code, event ->
            if (code in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) {
                if (event.action == KeyEvent.ACTION_UP) { editingVolume = !editingVolume; reveal(); volume.contentDescription = if (editingVolume) "Volume: use esquerda e direita. OK para concluir." else "Volume. OK para ajustar." }
                true
            } else if (code in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (editingVolume) volume.progress = (volume.progress + if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 5 else -5).coerceIn(0, 100)
                    else volume.focusSearch(if (code == KeyEvent.KEYCODE_DPAD_RIGHT) FOCUS_RIGHT else FOCUS_LEFT)?.requestFocus()
                    reveal()
                }
                true
            } else false
        }
        volume.setOnFocusChangeListener { _, focused -> if (!focused) editingVolume = false }
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutVideo() }
        jobs.run(work = { if (restart) null else db.checkpoint(api.credentials.account, initial) }, done = { progress ->
            if (!closed) { resumePosition = progress?.position ?: 0; start(initial, resumePosition, true) }
        }, failed = { if (!closed) start(initial, 0, true) })
        handler.post(tick)
        post { reveal(); pause.requestFocus() }
    }

    private fun iconButton(symbol: String, name: String, action: () -> Unit): Button = context.button("", symbol = symbol, action = action).apply {
        contentDescription = name; pad(9, 8)
    }
    private fun duration(): Long = engine?.duration?.takeIf { it > 0 } ?: 0
    private fun position(): Long = engine?.currentPosition?.coerceAtLeast(0) ?: resumePosition
    private fun start(content: Content, position: Long, play: Boolean) {
        if (closed) return
        if (!active) { item = content; resumePosition = position; playOnResume = play; return }
        if (engine == null) {
            val source = DefaultMediaSourceFactory(context).setDataSourceFactory(DefaultHttpDataSource.Factory().setUserAgent(XtreamApi.USER_AGENT).setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(15000).setReadTimeoutMs(25000))
            val control = DefaultLoadControl.Builder().setBufferDurationsMs(15000, 30000, 1000, 3000).setTargetBufferBytes(12 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build()
            engine = ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true)).setMediaSourceFactory(source).setLoadControl(control).build().also { player ->
                player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                player.setHandleAudioBecomingNoisy(true)
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setMaxVideoSize(1920, 1080).build()
                player.addListener(listener); video.player = player
                mediaSession = MediaSession.Builder(context, player).build()
            }
        }
        val player = engine ?: return
        player.stop(); player.clearMediaItems()
        item = content; title.text = item.displayName; resumePosition = position; ended = false; errorVisible = false
        messagePanel.visibility = VISIBLE; status.text = "Conectando à transmissão…"; retry.visibility = GONE
        player.volume = volume.progress / 100f
        player.setMediaItem(MediaItem.fromUri(api.playbackUrl(content)), position)
        player.prepare(); player.playWhenReady = play
        accountChanged()
        updatePosition()
    }
    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (closed) return
            when (state) {
                Player.STATE_BUFFERING -> { status.text = "Carregando a transmissão…"; messagePanel.visibility = VISIBLE }
                Player.STATE_READY -> { if (!errorVisible) messagePanel.visibility = GONE; timeline.isEnabled = item.kind != "live" && engine?.isCurrentMediaItemSeekable == true }
                Player.STATE_ENDED -> if (!ended) {
                    ended = true; saveProgress(true)
                    val index = queue.indexOfFirst { it.uid == item.uid }
                    if (item.kind == "episode" && db.autoNext && index >= 0 && index + 1 < queue.size) {
                        start(queue[index + 1], 0, true)
                    } else {
                        status.text = if (item.kind == "episode" && db.autoNext && index == queue.lastIndex && index >= 0) "Você chegou ao último episódio disponível." else "Reprodução concluída."
                        messagePanel.visibility = VISIBLE; reveal()
                    }
                }
            }
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            keepScreenOn = isPlaying
            pause.setCompoundDrawablesWithIntrinsicBounds(Glyph(if (isPlaying) "pause" else "play", Palette.text, context.dp(19)), null, null, null)
            pause.contentDescription = if (isPlaying) "Pausar" else "Reproduzir"
            if (!isPlaying && !closed) saveProgress()
        }
        override fun onPlayerError(error: PlaybackException) {
            if (closed) return
            errorVisible = true; saveProgress()
            status.text = if (error.errorCode in 4000..4999) "Este formato não pôde ser reproduzido neste aparelho. Tente outra qualidade ou outro conteúdo." else "Não foi possível reproduzir. Confira a conexão e o limite de telas da sua conta."
            messagePanel.visibility = VISIBLE; retry.visibility = VISIBLE; reveal(); retry.requestFocus()
        }
    }
    fun saveProgress(finished: Boolean = false) {
        if (engine == null) return
        val current = item
        val completed = finished || ended
        val time = if (completed) duration().takeIf { it > 0 } ?: resumePosition else position()
        val length = duration()
        if (engine != null) resumePosition = time
        lastSaved = System.currentTimeMillis()
        jobs.write { db.record(api.credentials.account, current, time, length, completed) }
    }
    private fun updatePosition() {
        val length = duration()
        time.text = "${stamp(position())} / ${if (item.kind == "live") "AO VIVO" else stamp(length)}"
        if (!scrubbing) timeline.progress = if (length > 0) (position() * 10000 / length).toInt().coerceIn(0, 10000) else 0
    }
    private fun seek(value: Long) {
        if (item.kind == "live" || engine?.isCurrentMediaItemSeekable != true) return
        ended = false; engine?.seekTo(value.coerceIn(0, duration())); saveProgress(); updatePosition()
    }
    fun skip(delta: Long) { seek(position() + delta); reveal() }
    fun togglePause() {
        val player = engine ?: return
        if (player.isPlaying) { player.pause(); saveProgress() }
        else { if (ended) { ended = false; player.seekTo(0) }; player.play() }
        reveal()
    }
    fun reveal() {
        if (closed) return
        controlsVisible = true; header.visibility = VISIBLE; footer.visibility = VISIBLE
        handler.removeCallbacks(hide)
        if (fullScreen) handler.postDelayed(hide, 3000)
    }
    fun hideControls() {
        if (!fullScreen || menuOpen || scrubbing || errorVisible) { reveal(); return }
        controlsVisible = false; header.visibility = GONE; footer.visibility = GONE; requestFocus()
    }
    private fun layoutVideo() {
        val params = video.layoutParams as LayoutParams
        val top = if (fullScreen) 0 else context.dp(60)
        val bottom = if (fullScreen) 0 else context.dp(92)
        if (params.topMargin != top || params.bottomMargin != bottom) { params.topMargin = top; params.bottomMargin = bottom; video.layoutParams = params }
    }
    fun handleKey(event: KeyEvent): Boolean {
        val key = event.keyCode
        val transport = key in listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND)
        if (transport) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) when (key) {
                KeyEvent.KEYCODE_MEDIA_PLAY -> { engine?.play(); reveal() }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { engine?.pause(); saveProgress(); reveal() }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> skip(10000)
                KeyEvent.KEYCODE_MEDIA_REWIND -> skip(-10000)
                else -> togglePause()
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && key in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) {
            val wasVisible = controlsVisible
            reveal()
            if (!wasVisible) {
                if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) { skip(if (key == KeyEvent.KEYCODE_DPAD_LEFT) -10000 else 10000); timeline.requestFocus() } else pause.requestFocus()
                return true
            }
        }
        if (key == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) { trackMenu(C.TRACK_TYPE_AUDIO); return true }
        return false
    }
    fun back(): Boolean = if (editingVolume) { editingVolume = false; reveal(); true } else if (controlsVisible && fullScreen && !errorVisible) { hideControls(); true } else false
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean { if (event.action == MotionEvent.ACTION_HOVER_MOVE || event.action == MotionEvent.ACTION_SCROLL) reveal(); return super.dispatchGenericMotionEvent(event) }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean { if (event.actionMasked in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE)) reveal(); return super.dispatchTouchEvent(event) }

    private fun trackMenu(type: Int) {
        val player = engine ?: return
        reveal(); menuOpen = true
        val choices = mutableListOf<Pair<String, (() -> Unit)?>>()
        if (type == C.TRACK_TYPE_TEXT) choices += "Desativadas" to { player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type).setTrackTypeDisabled(type, true).build() }
        for (group in player.currentTracks.groups.filter { it.type == type }) for (index in 0 until group.length) {
            if (!group.isTrackSupported(index)) continue
            val format = group.getTrackFormat(index)
            val language = format.language?.takeIf { it != "und" }?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale("pt", "BR")) }
            val name = listOfNotNull(format.label?.takeIf { it.isNotBlank() }, language).distinct().joinToString(" · ").ifBlank { "Faixa ${choices.size + 1}" }
            choices += (if (group.isTrackSelected(index)) "✓ $name" else name) to {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type).setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)).build()
            }
        }
        if (choices.isEmpty()) choices += "Nenhuma faixa disponível" to null
        val dialog = AlertDialog.Builder(context).setTitle(if (type == C.TRACK_TYPE_TEXT) "Legendas" else "Faixa de áudio")
            .setItems(choices.map { it.first }.toTypedArray()) { _, which -> choices[which].second?.invoke() }.create()
        dialog.setOnDismissListener { menuOpen = false; reveal() }; dialog.show()
        dialog.window?.setBackgroundDrawable(shape(Palette.panel, context.dp(12).toFloat()))
    }
    fun suspendPlayback() {
        active = false
        handler.removeCallbacks(tick)
        if (closed || engine == null) return
        playOnResume = engine?.playWhenReady == true; saveProgress()
        handler.removeCallbacks(hide); mediaSession?.release(); mediaSession = null
        video.player = null; engine?.removeListener(listener); engine?.release(); engine = null; keepScreenOn = false
    }
    fun resumePlayback() { active = true; if (!closed && engine == null) { start(item, resumePosition, playOnResume); handler.removeCallbacks(tick); handler.post(tick); reveal() } }
    fun shutdown() { if (closed) return; saveProgress(); closed = true; handler.removeCallbacksAndMessages(null); mediaSession?.release(); mediaSession = null; video.player = null; engine?.removeListener(listener); engine?.release(); engine = null; keepScreenOn = false }
}
