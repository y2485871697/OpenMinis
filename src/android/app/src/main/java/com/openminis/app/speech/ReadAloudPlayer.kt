package com.openminis.app.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.widget.Toast
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.voice.VoiceOutputRequest
import com.openminis.app.provider.voice.VoiceProviderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * [T-android-provider-tts-readaloud] Read-aloud playback that routes through
 * the resolved Voice OUTPUT selection, falling back to the on-device engine.
 *
 * ## Why this exists
 * Read-aloud previously constructed a bare [TextToSpeechManager], so it always
 * used the on-device Android engine. The whole provider TTS stack —
 * `VoiceProvider.synthesize()` plus ~723 lines of vendor adapters (MiniMax,
 * Doubao, ElevenLabs, Azure, Gemini, Xunfei, …) — was reachable only from the
 * settings Quick Test sheet, so no provider voice was usable in chat.
 *
 * ## Routing
 * Each utterance asks [com.openminis.app.data.repository.ProviderRepository
 * .resolveVoiceOutputChoice] (added in the P0-1 commit) where to go:
 *  - System choice, no credentials, unsupported vendor, or ANY synthesis
 *    failure → [TextToSpeechManager] (the on-device engine).
 *  - Provider choice → `synthesize()` then play the returned bytes.
 *
 * Fallback is per-utterance and silent by design: a mid-reply network blip
 * degrades to the device voice rather than dropping the sentence.
 *
 * ## Ordering
 * Utterances go through a [Channel] consumed by a single worker coroutine, so
 * sentence N+1 is synthesized/played only after N finishes. Without this,
 * concurrent `synthesize()` calls would return out of order and the reply would
 * be read scrambled. This is the Android stand-in for iOS's
 * `VoiceOutputPlayer` queue (we deliberately do NOT prefetch-ahead yet — that
 * is P1 territory).
 *
 * Text is sanitized via [VoiceTextSanitizer] before either path, so Markdown,
 * URLs and emoji are never pronounced.
 *
 * Not thread-safe beyond the channel; call from the main thread.
 */
class ReadAloudPlayer(context: Context) {

    private val appContext = context.applicationContext
    private val system = TextToSpeechManager().also { it.init(appContext) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue = Channel<String>(Channel.UNLIMITED)
    private var worker: Job? = null

    /** Currently playing provider audio, if any — held so [stop] can cancel it. */
    private var player: MediaPlayer? = null

    /**
     * Completes the in-flight [playBytes] suspension when [stop] tears the
     * MediaPlayer down. Releasing the player means its completion/error
     * callbacks will never fire, so without this the worker coroutine would
     * wait forever and the queue would wedge.
     */
    private var playbackFinisher: (() -> Unit)? = null

    /**
     * [T-android-tts-broken-listener] Latched once the system engine misses a
     * completion callback. Some OEM engines speak fine but never deliver
     * UtteranceProgressListener events; without this every sentence waited out
     * its whole (>=10 s) budget and was then cut off mid-word.
     */
    @Volatile
    private var progressListenerBroken = false

    /** Utterances enqueued but not yet finished; drives [isSpeaking]. */
    private val pending = AtomicInteger(0)

    /**
     * [T-android-tts-player-registry] True when THIS player owns the capsule.
     *
     * Two players can be alive at once (the reply channel, and the selection
     * Read-Aloud player). Both used to write the shared
     * isSpeaking/isSynthesizing/activeModelLabel mirrors unconditionally, so a
     * background player's state flickered the capsule of the foreground one.
     * Only the registry's top may drive the UI.
     */
    private fun ownsCapsule(): Boolean = VoiceOutputState.isActivePlayer(this)

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()
    private val _isSynthesizing = MutableStateFlow(false)
    val isSynthesizing: StateFlow<Boolean> = _isSynthesizing.asStateFlow()
    private val _activeModelLabel = MutableStateFlow<String?>(null)
    internal val activeModelLabel: StateFlow<String?> = _activeModelLabel.asStateFlow()
    internal fun hasActiveOutput(): Boolean =
        _isSpeaking.value || _isPaused.value || _isSynthesizing.value
    private val _currentChunkIndex = MutableStateFlow(0)
    val currentChunkIndex: StateFlow<Int> = _currentChunkIndex.asStateFlow()
    private val _totalChunks = MutableStateFlow(0)
    val totalChunks: StateFlow<Int> = _totalChunks.asStateFlow()
    private val _playbackProgress = MutableStateFlow(0f)
    /** Overall reply progress, always finite and clamped to 0..1 for Compose. */
    val playbackProgress: StateFlow<Float> = _playbackProgress.asStateFlow()
    @Volatile private var currentUtterance: String? = null
    @Volatile private var resumeSystemUtterance: String? = null
    private var progressJob: Job? = null

    // Android's system TTS has no seek API. These flags let the active
    // speakViaSystem loop stop the engine, calculate a new text offset and
    // restart in place instead of treating fast-forward as a completed reply.
    @Volatile private var pendingSystemFastForwardMs = 0
    @Volatile private var restartSystemForSpeed = false
    @Volatile private var currentSystemFraction = 0f

    /**
     * Rolling buffer for streaming input. Mirrors [TextToSpeechManager]'s own
     * buffer, but lives here so the sentence split happens BEFORE the routing
     * decision (each sentence is independently a provider or system utterance).
     */
    private val sentenceBuffer = StringBuilder()

    /**
     * [T-android-tts-stop-on-capture] Utterances parked by [suspendForCapture]
     * while the microphone is open, replayed by [resumeAfterCapture].
     *
     * Guarded by the same main-thread confinement the rest of this class
     * relies on (see the queue-state note on [stop]) — both entry points are
     * driven from the recognizer's state flow, which is collected on main.
     */
    private val heldForCapture = mutableListOf<String>()

    /** True between [suspendForCapture] and [resumeAfterCapture]/[stop]. */
    @Volatile
    private var capturePaused = false

    private val linkPhrases by lazy {
        VoiceTextSanitizer.LinkPhrases(
            linkToHost = appContext.getString(R.string.voice_link_to_host),
            bareLink = appContext.getString(R.string.voice_bare_link),
        )
    }

    init {
        // Construction only registers. enqueue() promotes this instance once
        // it has accepted real speech, so an idle lazy player cannot steal the
        // shared capsule from another player.
        VoiceOutputState.init(appContext)
        VoiceOutputState.registerPlayer(this)
        worker = scope.launch {
            for (text in queue) {
                while (_isPaused.value) delay(40)
                currentUtterance = text
                _currentChunkIndex.value = (_currentChunkIndex.value + 1)
                    .coerceAtMost(_totalChunks.value.coerceAtLeast(1))
                updateCurrentChunkProgress(0f)
                runCatching { speakOne(text) }
                    .onFailure { AppLogger.error(TAG, "utterance failed: $it") }
                updateCurrentChunkProgress(1f)
                currentUtterance = null
                // Queue drained → speech is over. Counter rather than
                // Channel.isEmpty, which is experimental API.
                if (pending.decrementAndGet() <= 0) {
                    _isSpeaking.value = false
                    _activeModelLabel.value = null
                    if (ownsCapsule()) VoiceOutputState.isSpeaking.value = false
                    VoiceOutputState.playerBecameIdle(this@ReadAloudPlayer)
                }
            }
        }
    }

    /**
     * Feed a chunk of streaming assistant output. Complete sentences are
     * enqueued as they appear so the first one starts speaking the moment its
     * terminator arrives, instead of waiting for the whole reply.
     */
    fun appendText(chunk: String) {
        if (chunk.isEmpty()) return
        sentenceBuffer.append(chunk)
        for (sentence in extractCompleteSentences(sentenceBuffer)) {
            enqueue(sentence)
        }
    }

    /** Emit the buffered tail (a fragment with no terminator). Call at stream end. */
    fun flush() {
        if (sentenceBuffer.isEmpty()) return
        // [T-android-tts-scope-align] Terminal extraction first (streaming =
        // false): a held-back unclosed code fence is dropped here rather than
        // spoken raw, and a trailing decimal look-ahead commits. Only the
        // genuine no-terminator remainder goes out as the tail unit.
        for (s in SpeechSentenceSplitter.extractCompleteSentences(sentenceBuffer, streaming = false)) {
            enqueue(s)
        }
        val tail = sentenceBuffer.toString().trim()
        sentenceBuffer.setLength(0)
        if (tail.isNotEmpty()) enqueue(tail)
    }

    /** Speak [text] as a single utterance, after stopping anything in flight. */
    fun speak(text: String) {
        stop()
        _currentChunkIndex.value = 0
        _totalChunks.value = 0
        _playbackProgress.value = 0f
        enqueue(text)
    }

    /**
     * Speak [text] AFTER whatever is already queued, without interrupting it.
     *
     * [speak] stops playback first, which is right for a user-initiated "read
     * this" but wrong for an announcement that has to slot into a running
     * narration — a tool announcement must follow the sentence that preceded
     * the tool call and precede whatever the model says next. Mirrors iOS
     * `speakQueued` (AIChatViewModel+SSEStream), which exists for the same
     * reason.
     *
     * Goes through [enqueue], so it inherits the mute / mic-capture / sanitize
     * gating rather than re-deriving it.
     */
    fun speakQueued(text: String) {
        if (text.isBlank()) return
        enqueue(text)
    }

    private fun enqueue(raw: String) {
        // [T-android-tts-capsule] Temporary mute suppresses NEW utterances too
        // (not only current playback) — matching iOS, where `canPlay =
        // isEnabled && !isMuted` gates production. Dropping here rather than at
        // playback keeps a long mute from piling up a backlog that would all
        // blurt out on un-mute.
        if (!VoiceOutputState.canPlay) return
        // [T-android-tts-scope-align] Mic-capture suppression, from iOS
        // canSpeakNow (feedDynamicTTS logs "DROP — read-replies off
        // (capturing)"): while the VAD/recognizer is actively capturing,
        // reply TTS must not play — the speaker feeds straight back into the
        // dictation mic (echo), and capture/playback also fight over the
        // audio route. iOS drops the text outright; mirrored here.
        val recState = SpeechRecognitionManager.state.value
        if (recState == RecognitionState.RECORDING || recState == RecognitionState.STARTING) {
            AppLogger.info(TAG, "enqueue dropped — mic capturing (state=$recState)")
            return
        }
        // Sanitize here, not at playback: a chunk that is pure Markdown (e.g. a
        // fenced code block) sanitizes to empty and must not occupy the queue.
        val clean = VoiceTextSanitizer.sanitize(raw, linkPhrases)
        if (clean.isBlank()) return
        // A lazily-created but idle player must not own the shared controller.
        // Promote only once this instance has accepted real speech work.
        VoiceOutputState.promotePlayer(this)
        pending.incrementAndGet()
        _totalChunks.value = _totalChunks.value + 1
        _isSpeaking.value = true
        if (ownsCapsule()) VoiceOutputState.isSpeaking.value = true
        queue.trySend(clean)
    }

    /**
     * [T-android-tts-stop-on-capture] Silence playback for a dictation turn,
     * KEEPING what has not been spoken yet so [resumeAfterCapture] can pick it
     * back up.
     *
     * Distinct from [stop], which drops the queue: this is a pause, not a
     * cancel. The utterance that is mid-playback is abandoned (there is no
     * seek-within-utterance on either engine, and re-speaking a whole paragraph
     * to recover two words is worse than losing them), but everything still
     * queued behind it is preserved and re-queued on resume.
     */
    fun suspendForCapture() {
        if (capturePaused) return
        capturePaused = true
        // Drain into the holding list rather than onto the floor.
        val held = mutableListOf<String>()
        while (true) {
            val r = queue.tryReceive()
            if (r.isSuccess) held.add(r.getOrThrow()) else break
        }
        // The un-terminated tail counts as pending speech too.
        val tail = sentenceBuffer.toString().trim()
        sentenceBuffer.setLength(0)
        if (tail.isNotEmpty()) held.add(tail)
        heldForCapture.addAll(held)

        playbackFinisher?.invoke()
        progressJob?.cancel()
        progressJob = null
        releasePlayer()
        system.stop()
        pending.set(0)
        _isSpeaking.value = false
        _isSynthesizing.value = false
        _activeModelLabel.value = null
        if (ownsCapsule()) {
            VoiceOutputState.isSpeaking.value = false
            VoiceOutputState.isSynthesizing.value = false
        }
        VoiceOutputState.playerBecameIdle(this)
        AppLogger.info(TAG, "suspended for mic capture — held ${heldForCapture.size} utterance(s)")
    }

    /** Re-queue whatever [suspendForCapture] held back. */
    fun resumeAfterCapture() {
        if (!capturePaused) return
        capturePaused = false
        if (heldForCapture.isEmpty()) return
        val toSpeak = heldForCapture.toList()
        heldForCapture.clear()
        // Back through enqueue so the mute / still-capturing / sanitize gates
        // all re-apply — the user may have muted or started talking again
        // while we were paused.
        toSpeak.forEach { enqueue(it) }
        AppLogger.info(TAG, "resumed after mic capture — re-queued ${toSpeak.size} utterance(s)")
    }

    /** Stop playback and drop everything pending. */
    fun stop() {
        // A real stop supersedes a capture pause: nothing held is still wanted.
        capturePaused = false
        heldForCapture.clear()
        sentenceBuffer.setLength(0)
        while (queue.tryReceive().isSuccess) { /* drain */ }
        // Unblock the worker BEFORE releasing, so the in-flight utterance's
        // suspension completes instead of waiting on callbacks that a released
        // MediaPlayer will never deliver.
        playbackFinisher?.invoke()
        releasePlayer()
        system.stop()
        pending.set(0)
        _isPaused.value = false
        currentUtterance = null
        resumeSystemUtterance = null
        pendingSystemFastForwardMs = 0
        restartSystemForSpeed = false
        currentSystemFraction = 0f
        _currentChunkIndex.value = 0
        _totalChunks.value = 0
        _playbackProgress.value = 0f
        _isSpeaking.value = false
        _isSynthesizing.value = false
        _activeModelLabel.value = null
        if (ownsCapsule()) {
            VoiceOutputState.isSpeaking.value = false
            VoiceOutputState.isSynthesizing.value = false
        }
        VoiceOutputState.playerBecameIdle(this)
    }

    /** Pause provider audio precisely; system TTS resumes the current sentence. */
    fun pause() {
        if (_isPaused.value || !_isSpeaking.value) return
        _isPaused.value = true
        val media = player
        if (media != null) {
            runCatching { if (media.isPlaying) media.pause() }
        } else {
            resumeSystemUtterance = currentUtterance
            system.stop()
        }
        _isSpeaking.value = false
        if (ownsCapsule()) VoiceOutputState.isSpeaking.value = false
    }

    fun resume() {
        if (!_isPaused.value) return
        _isPaused.value = false
        val media = player
        if (media != null) {
            runCatching { media.start() }
            _isSpeaking.value = true
            if (ownsCapsule()) VoiceOutputState.isSpeaking.value = true
            return
        }
        resumeSystemUtterance?.let { text ->
            resumeSystemUtterance = null
            enqueue(text)
        }
    }

    /** Apply a capsule speed change to the utterance that is playing now. */
    fun applyPlaybackSpeed(value: Float) {
        val safe = value.coerceIn(0.5f, 3.0f)
        system.speechRate = safe
        val media = player
        if (media != null) {
            val keepPaused = _isPaused.value
            runCatching {
                media.playbackParams = media.playbackParams.setSpeed(safe)
                if (keepPaused) media.pause()
            }.onFailure {
                AppLogger.error(TAG, "live playback speed $safe rejected by MediaPlayer: $it")
            }
            return
        }
        if (currentUtterance != null && (system.isSpeaking.value || _isPaused.value)) {
            restartSystemForSpeed = true
            system.stop()
        }
    }

    /** Advance to the next queued sentence. */
    fun skipNext() {
        playbackFinisher?.invoke()
        releasePlayer()
        system.stop()
        resumeSystemUtterance = null
        _isPaused.value = false
    }

    /** Seek provider audio; system TTS restarts from an estimated text offset. */
    fun fastForward(milliseconds: Int = 5_000) {
        if (milliseconds <= 0) return
        val media = player
        if (media != null) {
            runCatching {
                media.seekTo((media.currentPosition + milliseconds).coerceAtMost(media.duration))
            }
        } else if (currentUtterance != null) {
            pendingSystemFastForwardMs =
                (pendingSystemFastForwardMs + milliseconds).coerceAtMost(60_000)
            // Wakes speakViaSystem immediately. It consumes the request and
            // re-speaks the remaining text, so the capsule stays mounted.
            system.stop()
        }
    }

    /** Release both engines. Call from the owner's DisposableEffect. */
    fun shutdown() {
        stop()
        worker?.cancel()
        queue.close()
        scope.cancel()
        system.shutdown()
        // Deregister by identity: removing a mid-stack player leaves the rest
        // intact, and dropping the top hands the capsule back to the player
        // beneath (typically the reply channel) instead of orphaning it.
        VoiceOutputState.unregisterPlayer(this)
    }

    // -- internals --

    private suspend fun speakOne(text: String) {
        val entry = runCatching {
            (appContext as? MinisApp)?.providerRepository?.resolveVoiceOutputEntry()
        }.getOrNull()

        if (entry != null) {
            val (instance, modelEntry) = entry
            // [T-android-tts-capsule] Surface the ACTUALLY-synthesizing model
            // on the capsule's chip (falls back to the system label below when
            // this path fails over).
            val modelLabel = modelEntry.model.displayName.ifBlank { modelEntry.model.id }
            _activeModelLabel.value = modelLabel
            if (ownsCapsule()) VoiceOutputState.activeModelLabel.value = modelLabel
            val ok = runCatching { speakViaProvider(instance, modelEntry, text) }
                .getOrElse {
                    AppLogger.error(
                        TAG,
                        "provider TTS failed (model=${modelEntry.model.id} " +
                            "vendor=${instance.providerType}), falling back to system engine: $it",
                    )
                    false
                }
            if (ok) return
        }
        _activeModelLabel.value = null
        if (ownsCapsule()) VoiceOutputState.activeModelLabel.value = null // system engine
        if (!speakViaSystem(text)) {
            // [T-android-tts-silent-blackhole] Terminal state: the provider path
            // did not produce audio AND the device speech engine is unusable.
            // iOS can't reach this (AVSpeechSynthesizer always exists), which is
            // why read-aloud "just works" there while Android could go silent
            // with no feedback at all — the reported OPPO symptom. Tell the user
            // once instead of swallowing it.
            notifySpeechUnavailable(providerConfigured = entry != null)
        }
    }

    /** One-shot user-visible signal that read-aloud cannot produce sound. */
    private var unavailableNotified = false

    private fun notifySpeechUnavailable(providerConfigured: Boolean) {
        AppLogger.error(
            TAG,
            "read-aloud produced NO audio: providerConfigured=$providerConfigured " +
                "systemEngine=${if (system.initFailed) "init-failed" else "unavailable"}",
        )
        if (unavailableNotified) return
        unavailableNotified = true
        // The worker runs on Dispatchers.Main.immediate, so Toast is safe here.
        Toast.makeText(
            appContext,
            appContext.getString(R.string.read_aloud_unavailable),
            Toast.LENGTH_LONG,
        ).show()
    }

    /** @return true when provider audio was synthesized AND played. */
    private suspend fun speakViaProvider(
        instance: com.openminis.app.data.model.ProviderInstance,
        modelEntry: com.openminis.app.data.model.ModelEntry,
        text: String,
    ): Boolean {
        // [T-android-safemode-lateinit-crash-147] `?.` guards a null
        // Application but NOT an unassigned lateinit — the getter throws.
        // Read-aloud can be driven from a notification action with no
        // Activity, so gate on subsystemsReady() before touching a repository.
        // Each bail-out logs its reason: these gates used to return false
        // silently, which made "no sound" undiagnosable from a user device
        // (the daily log files showed nothing at all).
        val repo = (appContext as? MinisApp)
            ?.takeIf { it.subsystemsReady() }
            ?.providerRepository
            ?: return false.also { AppLogger.error(TAG, "provider TTS skipped: repository unavailable") }
        val apiKey = repo.loadApiKey(instance.id)
            ?: return false.also { AppLogger.error(TAG, "provider TTS skipped: no API key for ${instance.id}") }
        val voice = VoiceProviderFactory.make(instance, apiKey)
            ?: return false.also { AppLogger.error(TAG, "provider TTS skipped: no voice adapter for ${instance.providerType}/${instance.customBaseURL}") }
        if (!voice.supportsVoiceOutput) {
            AppLogger.error(TAG, "provider TTS skipped: ${voice.javaClass.simpleName} does not support output")
            return false
        }

        // [T-android-tts-capsule] Drives the capsule's rotating-arc loading
        // indicator while the network request is in flight (iOS
        // voicePlayer.isSynthesizing).
        _isSynthesizing.value = true
        if (ownsCapsule()) VoiceOutputState.isSynthesizing.value = true
        val data = try {
            withContext(Dispatchers.IO) {
                voice.synthesize(
                    // The entry id doubles as the voice id — template voices carry
                    // the voice id as the model id (same convention QuickTestSheet
                    // relies on, iOS 0a52bdbf).
                    VoiceOutputRequest(
                        input = text,
                        model = modelEntry.model.id,
                        voice = modelEntry.model.id,
                    ),
                )
            }
        } finally {
            _isSynthesizing.value = false
            if (ownsCapsule()) VoiceOutputState.isSynthesizing.value = false
        }
        if (data.isEmpty()) {
            AppLogger.error(TAG, "provider TTS returned empty audio (model=${modelEntry.model.id})")
            return false
        }
        playBytes(data)
        return true
    }

    /**
     * Write [data] to a cache file and play it to completion. Same approach as
     * QuickTestSheet — MediaPlayer needs a file/descriptor, not a byte array.
     */
    private suspend fun playBytes(data: ByteArray) {
        val file = withContext(Dispatchers.IO) {
            File(appContext.cacheDir, "readaloud_tts.audio").apply { writeBytes(data) }
        }
        suspendCancellableCoroutine { cont ->
            val mp = MediaPlayer()
            player = mp
            var resumed = false
            fun finish() {
                if (resumed) return
                resumed = true
                playbackFinisher = null
                progressJob?.cancel()
                progressJob = null
                releasePlayer()
                if (cont.isActive) cont.resume(Unit)
            }
            // Let stop()/shutdown() complete this suspension after releasing
            // the player, since the callbacks below can no longer fire.
            playbackFinisher = ::finish
            runCatching {
                // [T-android-tts-silent-blackhole] Explicit attributes + audio
                // focus. iOS routes playback through AudioSessionCoordinator;
                // Android had neither, and some OEM ROMs (ColorOS among them)
                // will silently mute focus-less media playback. Focus denial is
                // logged but non-fatal — muting is the exception, not the rule.
                mp.setAudioAttributes(playbackAttributes)
                requestAudioFocus()
                mp.setDataSource(file.absolutePath)
                mp.setOnCompletionListener { finish() }
                mp.setOnErrorListener { _, what, extra ->
                    AppLogger.error(TAG, "MediaPlayer error what=$what extra=$extra")
                    finish()
                    true
                }
                mp.prepare()
                // [T-android-tts-rom-compat] start() FIRST, speed second, each
                // in its own guard. The previous order relied on
                // setPlaybackParams-on-prepared implicitly starting playback —
                // documented AOSP behaviour, but some OEM MediaPlayer builds
                // throw IllegalStateException there, and the failure landed in
                // the OUTER runCatching which skipped the utterance entirely.
                // Now a ROM that rejects the speed change still plays at 1×;
                // only the speed is lost, never the audio.
                mp.start()
                progressJob?.cancel()
                progressJob = scope.launch {
                    while (player === mp) {
                        val duration = runCatching { mp.duration }.getOrDefault(0)
                        val position = runCatching { mp.currentPosition }.getOrDefault(0)
                        val fraction = if (duration > 0) {
                            position.toFloat() / duration.toFloat()
                        } else {
                            0f
                        }
                        updateCurrentChunkProgress(fraction)
                        delay(POLL_MS)
                    }
                }
                val speed = VoiceOutputState.speed.value
                if (speed != 1.0f) {
                    runCatching {
                        mp.playbackParams = mp.playbackParams.setSpeed(speed)
                    }.onFailure {
                        AppLogger.error(TAG, "playback speed $speed rejected by this ROM's MediaPlayer, playing at 1×: $it")
                    }
                }
            }.onFailure {
                AppLogger.error(TAG, "MediaPlayer setup failed: $it")
                finish()
            }
            cont.invokeOnCancellation { releasePlayer() }
        }
    }

    // -- audio focus [T-android-tts-silent-blackhole] --

    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val playbackAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var focusRequest: AudioFocusRequest? = null

    private fun requestAudioFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(playbackAttributes)
            .build()
        focusRequest = req
        val granted = audioManager.requestAudioFocus(req)
        if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            AppLogger.info(TAG, "audio focus not granted ($granted) — playing anyway")
        }
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    /**
     * Speak via the on-device engine and wait for it to finish, so the queue
     * stays ordered. [TextToSpeechManager.isSpeaking] flips false on the last
     * utterance's onDone.
     *
     * @return false when the engine is unusable (init failed / never bound) —
     *   the caller surfaces that instead of pretending the sentence was spoken.
     */
    private suspend fun speakViaSystem(text: String): Boolean {
        // [T-android-tts-silent-blackhole] Engine binding is async; speaking
        // before it settles used to drop the text on the floor. Wait (bounded)
        // for a verdict first.
        if (!system.awaitReady()) return false
        // The loop may restart a system utterance after a speed/seek request;
        // each pass applies the latest rate before speaking the remainder.
        var remaining = text
        var consumedChars = 0
        while (remaining.isNotBlank()) {
            pendingSystemFastForwardMs = 0
            restartSystemForSpeed = false
            currentSystemFraction = 0f
            val speed = VoiceOutputState.speed.value.coerceIn(0.5f, 3.0f)
            system.speechRate = speed
            // [T-android-system-voice-catalog] Apply the picked system voice
            // before every restart as well as the initial utterance.
            system.preferredVoiceName = VoiceOutputState.systemVoiceName.value
            if (ownsCapsule()) {
                VoiceOutputState.activeModelLabel.value = VoiceOutputState.systemVoiceLabel.value
            }
            system.speak(remaining)
            if (!system.isSpeaking.value) return false

            val estimatedMs = estimatedSpeechMs(remaining, speed)
            val started = System.currentTimeMillis()
            val budgetMs = (remaining.length * 600L + 10_000L).coerceAtMost(180_000L)
            val deadline = started + budgetMs
            while (system.isSpeaking.value || progressListenerBroken) {
                val elapsed = System.currentTimeMillis() - started
                val local = (elapsed.toFloat() / estimatedMs.toFloat()).coerceIn(0f, 0.98f)
                currentSystemFraction = local
                val overall = (consumedChars + remaining.length * local) / text.length.toFloat()
                updateCurrentChunkProgress(overall)
                if (pendingSystemFastForwardMs > 0 || restartSystemForSpeed || _isPaused.value) break
                if (progressListenerBroken && elapsed >= estimatedMs) break
                if (!progressListenerBroken && System.currentTimeMillis() > deadline) {
                    AppLogger.error(
                        TAG,
                        "system TTS completion never reported (len=${remaining.length}, " +
                            "waited ${budgetMs}ms); switching to estimated progress",
                    )
                    progressListenerBroken = true
                    break
                }
                delay(POLL_MS)
            }

            val fastForwardMs = pendingSystemFastForwardMs
            val shouldRestart = restartSystemForSpeed
            pendingSystemFastForwardMs = 0
            restartSystemForSpeed = false
            if (_isPaused.value) return true
            if (fastForwardMs > 0 || shouldRestart) {
                val elapsedChars = (remaining.length * currentSystemFraction).roundToInt()
                val forwardChars = if (fastForwardMs > 0) {
                    ceil(remaining.length * (fastForwardMs.toFloat() / estimatedMs.toFloat()))
                        .toInt()
                } else {
                    0
                }
                val advance = (elapsedChars + forwardChars).coerceIn(1, remaining.length)
                consumedChars = (consumedChars + advance).coerceAtMost(text.length)
                remaining = remaining.drop(advance).trimStart()
                updateCurrentChunkProgress(consumedChars.toFloat() / text.length.toFloat())
                continue
            }
            currentSystemFraction = 1f
            return true
        }
        currentSystemFraction = 1f
        return true
    }

    /**
     * Rough utterance duration for the broken-listener path. Deliberately on
     * the short side of real speech: overlapping slightly is far less bad than
     * the multi-second dead air the full budget produced.
     */
    private fun estimatedSpeechMs(text: String, speed: Float = VoiceOutputState.speed.value): Long =
        (((text.length * 90L + 400L) / speed.coerceIn(0.5f, 3.0f)).toLong())
            .coerceIn(300L, 60_000L)

    private fun updateCurrentChunkProgress(chunkFraction: Float) {
        val total = _totalChunks.value
        if (total <= 0) {
            _playbackProgress.value = 0f
            return
        }
        val completed = (_currentChunkIndex.value - 1).coerceAtLeast(0)
        val safeFraction = if (chunkFraction.isFinite()) chunkFraction.coerceIn(0f, 1f) else 0f
        _playbackProgress.value = ((completed + safeFraction) / total.toFloat()).coerceIn(0f, 1f)
    }

    private fun releasePlayer() {
        progressJob?.cancel()
        progressJob = null
        player?.runCatching {
            if (isPlaying) stop()
            release()
        }
        player = null
        abandonAudioFocus()
    }

    /**
     * [T-android-tts-intranumber-guard] Delegates to the shared
     * [SpeechSentenceSplitter] so this path and [TextToSpeechManager] apply the
     * same terminator set AND the same intra-number guards ("3.14" is never cut
     * into "3." + "14").
     */
    private fun extractCompleteSentences(buffer: StringBuilder): List<String> =
        SpeechSentenceSplitter.extractCompleteSentences(buffer)

    companion object {
        private const val TAG = "ReadAloud"
        private const val POLL_MS = 80L
    }
}
