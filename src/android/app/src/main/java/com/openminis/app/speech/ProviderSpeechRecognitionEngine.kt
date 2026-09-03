package com.openminis.app.speech

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.openminis.app.MinisApp
import com.openminis.app.provider.voice.VoiceInputRequest
import com.openminis.app.provider.voice.VoiceProvider
import com.openminis.app.provider.voice.VoiceProviderException
import com.openminis.app.provider.voice.VoiceProviderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * [T-android-provider-voice] Provider-backed transcription engine — the
 * formerly-stubbed Phase 2. Captures PCM16 mono 16 kHz via [AudioRecord],
 * wraps the take in a WAV container on stop, and ships it to the VoiceProvider
 * resolved from the Voice Input group (VoiceProviderFactory + the instance's
 * stored API key). Mirrors the iOS provider ASR path (VoiceProvider.transcribe:
 * dedicated Whisper-style endpoint, vendor adapters, or chat-based ASR for
 * audio chat models).
 *
 * No partial results: cloud transcription is one-shot on stop. The System
 * engine remains the streaming/live option.
 */
class ProviderSpeechRecognitionEngine(private val appContext: Context) : SpeechRecognitionEngine {

    companion object {
        private const val TAG = "ProviderASR"
        private const val SAMPLE_RATE = 16_000
        /** Hard cap on a single take (60 s at 16 kHz PCM16 mono ≈ 1.9 MB). */
        private const val MAX_RECORD_SECONDS = 60

        // ── [T-android-vad] ──
        /**
         * Cloud ASR has no per-request length ceiling the way Apple's system
         * recogniser does, so a segment may run to the full session cap
         * (iOS VoiceInputPanel.swift:488-489).
         */
        private const val CLOUD_MAX_SEGMENT_SECONDS = 300

        /**
         * Segments shorter than this are dropped on a silence close, matching
         * iOS `minSegmentSeconds` (VoiceInputPanel.swift:607). A cough or a
         * door would otherwise cost a paid transcription request.
         */
        private const val MIN_SEGMENT_SECONDS = 2.0f

        /** Below this we stay silent; above it the user gets told why (iOS :661). */
        private const val TOO_SHORT_TOAST_FLOOR = 0.3f

        /**
         * [T-android-vad-merge-segments] How long to keep the mic open waiting
         * for the user to top up a sub-2s utterance. Matches the iOS
         * force-flush timer (VoiceInputPanel.swift:609).
         */
        private const val HOLD_FLUSH_MS = 5_000L
    }

    override val id: String = "provider"
    override val displayName: String = "Provider transcription"
    override val supportsPartialResults: Boolean = false

    /** Cheap check only: is a provider ASR selection resolvable right now? */
    override val isAvailable: Boolean
        get() = !degraded && repository()?.resolveVoiceInputEntry() != null

    /** Cloud ASR is language-agnostic (auto-detect); no fixed locale list. */
    override val supportedLocales: List<Locale> = emptyList()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null
    private var transcribeJob: Job? = null
    private val recording = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val callbackGeneration = AtomicLong(0L)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var degraded = false

    private fun isCurrent(generation: Long): Boolean =
        callbackGeneration.get() == generation && !cancelled.get()

    /** SpeechRecognitionManager mutates Compose-facing state, so every engine
     * callback is delivered on main. The generation check also drops callbacks
     * already queued when a cancellation or replacement session wins the race. */
    private fun dispatchListener(
        generation: Long?,
        listener: SpeechRecognitionEngine.Listener,
        callback: SpeechRecognitionEngine.Listener.() -> Unit,
    ) {
        val task = Runnable {
            if (generation == null || isCurrent(generation)) callback(listener)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) task.run() else mainHandler.post(task)
    }

    /**
     * [T-android-vad] Live Silero detector for the segmented path, null when
     * idle or when running the legacy record-until-stop loop.
     */
    @Volatile
    private var detector: VoiceActivityDetector? = null

    /** Mutable state for one VAD capture. Every access is guarded by [lock]. */
    private class VadCaptureSession(
        val generation: Long,
        val locale: Locale,
        val repo: com.openminis.app.data.repository.ProviderRepository,
        val candidates: List<Pair<com.openminis.app.data.model.ProviderInstance, com.openminis.app.data.model.ModelEntry>>,
        val listener: SpeechRecognitionEngine.Listener,
    ) {
        val lock = Any()
        val pendingSegments = mutableListOf<ByteArray>()
        val rawPcm = ByteArrayOutputStream()
        var holdFlushJob: Job? = null
        var terminalClaimed = false
        var sawVoice = false
    }

    @Volatile
    private var vadSession: VadCaptureSession? = null

    /**
     * Whether to segment with Silero instead of recording until the user taps
     * stop. Defaults ON: this engine had no endpointing at all, so the VAD is
     * strictly better here. Kept as a flag so a device where the native VAD
     * fails to load can be dropped back to the legacy loop rather than losing
     * voice input entirely.
     */
    @Volatile
    var useVad: Boolean = true

    /**
     * [T-voice-asr-group-failover] STICKY fail-over (mirrors iOS
     * VoiceInputViewModel and the text agent loop's nextFallback): once a take
     * succeeds on a group member, later takes start there — only advance when
     * IT fails. Cleared implicitly when the entry leaves the candidate set
     * (the ordering below just falls back to chain order then).
     */
    @Volatile
    private var stickyEntryId: String? = null

    // [T-android-safemode-lateinit-crash-147] subsystemsReady() first — the
    // safe call rules out a null Application, not an unassigned lateinit,
    // whose getter throws. Speech recognition can be started from a
    // shortcut/assistant intent that never went through MainActivity's guard.
    // Every caller already treats null as "no provider configured".
    private fun repository() =
        (appContext.applicationContext as? MinisApp)
            ?.takeIf { it.subsystemsReady() }
            ?.providerRepository

    override fun markDegraded() {
        degraded = true
    }

    override fun clearDegraded() {
        degraded = false
    }

    @SuppressLint("MissingPermission") // caller ensures RECORD_AUDIO per interface contract
    override fun start(locale: Locale, listener: SpeechRecognitionEngine.Listener) {
        val repo = repository()
        // [T-voice-asr-group-failover] Resolve the whole ordered candidate
        // chain instead of one entry. loadBalance groups get a fresh rotation
        // seed per capture so takes spread across members; fallback groups
        // keep declaration order. Provider construction is deferred to the
        // transcription step, where a failing member advances to the next.
        val candidates = repo?.resolveVoiceInputCandidates(
            loadBalanceSeed = kotlin.random.Random.nextInt(Int.MAX_VALUE),
        ).orEmpty()
        if (repo == null || candidates.isEmpty()) {
            dispatchListener(null, listener) {
                onError(
                    RecognitionError.OEM_NO_SERVICE,
                    "No provider voice-input model configured. Add an ASR model to the Voice Input group.",
                )
            }
            return
        }
        if (recording.getAndSet(true)) {
            dispatchListener(null, listener) {
                onError(RecognitionError.RECOGNIZER_BUSY, "A capture is already in flight.")
            }
            return
        }
        val generation = callbackGeneration.incrementAndGet()
        cancelled.set(false)
        // A generation owns all VAD buffers and delayed work. Any stale state
        // is unreachable after this point and its callbacks are generation-gated.
        vadSession = null

        // [T-android-vad] VAD-segmented path. Before this, the provider engine
        // had NO endpointing at all: it recorded until the user tapped stop or
        // hit the 60 s cap, so a custom-model user had to manually bracket
        // every utterance. Silero now closes a segment after ~5 s of silence
        // and the mic stops, exactly as on iOS.
        if (useVad) {
            startVadCapture(locale, repo, candidates, listener, generation)
            return
        }

        captureJob = scope.launch {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) {
                if (isCurrent(generation)) recording.set(false)
                dispatchListener(generation, listener) {
                    onError(RecognitionError.AUDIO_ERROR, "AudioRecord unsupported buffer size.")
                }
                return@launch
            }
            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 4,
                )
            } catch (e: Exception) {
                if (isCurrent(generation)) recording.set(false)
                dispatchListener(generation, listener) {
                    onError(RecognitionError.AUDIO_ERROR, "AudioRecord init failed: ${e.message}")
                }
                return@launch
            }
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                if (isCurrent(generation)) recording.set(false)
                dispatchListener(generation, listener) {
                    onError(RecognitionError.AUDIO_ERROR, "AudioRecord not initialized.")
                }
                return@launch
            }

            val pcm = ByteArrayOutputStream()
            val buf = ByteArray(minBuf)
            val maxBytes = SAMPLE_RATE * 2 * MAX_RECORD_SECONDS
            try {
                recorder.startRecording()
                dispatchListener(generation, listener) { onReadyForSpeech() }
                while (recording.get() && pcm.size() < maxBytes) {
                    val n = recorder.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    pcm.write(buf, 0, n)
                    dispatchListener(generation, listener) { onRmsDb(rmsDb(buf, n)) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "capture failed: ${e.message}", e)
                if (isCurrent(generation)) recording.set(false)
                dispatchListener(generation, listener) {
                    onError(RecognitionError.AUDIO_ERROR, e.message)
                }
                return@launch
            } finally {
                runCatching { recorder.stop() }
                recorder.release()
            }

            if (!isCurrent(generation)) return@launch
            recording.set(false)
            val audio = pcm.toByteArray()
            if (audio.isEmpty()) {
                dispatchListener(generation, listener) {
                    onError(RecognitionError.NO_MATCH, "No audio captured.")
                }
                return@launch
            }

            // One-shot cloud transcription of the whole take, with SEAMLESS
            // fail-over across the candidate chain (mirrors iOS
            // transcribeWithFailover / VoiceOutputPlayer): a candidate that
            // throws advances to the next; the first success becomes the
            // STICKY member for later takes; only when EVERY candidate fails
            // does the error surface (last error wins).
            transcribeJob = scope.launch {
                val wav = VoiceProvider.wrapPcm16InWav(audio, SAMPLE_RATE)
                // Sticky-first try order: rotate the chain so the last
                // successful member goes first, the rest wrap around.
                val ordered = stickyEntryId
                    ?.let { sticky -> candidates.indexOfFirst { it.second.id == sticky } }
                    ?.takeIf { it > 0 }
                    ?.let { i -> candidates.drop(i) + candidates.take(i) }
                    ?: candidates
                var lastError: Exception? = null
                for ((instance, entry) in ordered) {
                    if (!isCurrent(generation)) return@launch
                    val provider = VoiceProviderFactory.make(instance, repo.loadApiKey(instance.id))
                    if (provider == null) {
                        Log.w(TAG, "candidate ${instance.label} cannot serve voice input — skipping")
                        continue
                    }
                    try {
                        val response = provider.transcribe(
                            VoiceInputRequest(
                                audioData = wav,
                                model = entry.baseModel.id,
                                language = locale.toLanguageTag(),
                                resolvedModel = entry.model,
                            ),
                        )
                        if (!isCurrent(generation)) return@launch
                        stickyEntryId = entry.id
                        val text = response.text.trim()
                        if (text.isEmpty()) {
                            // A successful round-trip that heard nothing is a
                            // semantic no-match, not a provider failure — do
                            // NOT advance the chain for it.
                            dispatchListener(generation, listener) {
                                onError(RecognitionError.NO_MATCH, "Empty transcription.")
                            }
                        } else {
                            dispatchListener(generation, listener) { onFinal(text) }
                        }
                        return@launch
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "transcribe failed on ${entry.model.displayName}: ${e.message} — trying next candidate")
                        lastError = e
                    }
                }
                if (isCurrent(generation)) {
                    val e = lastError
                    val kind = when {
                        e is VoiceProviderException.Auth -> RecognitionError.PERMISSION_DENIED
                        e is java.io.IOException -> RecognitionError.NETWORK
                        else -> RecognitionError.UNKNOWN
                    }
                    dispatchListener(generation, listener) {
                        onError(kind, e?.message ?: "All voice-input candidates failed.")
                    }
                }
            }
        }
    }

    // ── [T-android-vad] VAD-segmented capture ─────────────────────────────

    /**
     * Silence-segmented capture. Silero closes a segment after ~5 s of silence;
     * we then transcribe that segment and STOP the mic, matching iOS
     * (`VoiceInputPanel.swift:642-669`) — the user re-taps for the next
     * utterance rather than the mic staying hot.
     */
    private fun startVadCapture(
        locale: Locale,
        repo: com.openminis.app.data.repository.ProviderRepository,
        candidates: List<Pair<com.openminis.app.data.model.ProviderInstance, com.openminis.app.data.model.ModelEntry>>,
        listener: SpeechRecognitionEngine.Listener,
        generation: Long,
    ) {
        val session = VadCaptureSession(generation, locale, repo, candidates, listener)
        vadSession = session
        val det = VoiceActivityDetector(
            appContext,
            object : VoiceActivityListener {
                override fun onVoiceStart() {
                    if (!isCurrent(generation)) return
                    val staleFlush = synchronized(session.lock) {
                        if (session.terminalClaimed) return
                        session.sawVoice = true
                        session.holdFlushJob.also { session.holdFlushJob = null }
                    }
                    staleFlush?.cancel()
                    Log.i(TAG, "[vad] speech start")
                }

                override fun onLevel(level: Float) {
                    // Map the detector's perceptual [0,1] back onto the [0,12]
                    // dB-ish scale SpeechRecognitionManager normalizes from, so
                    // both engines drive the waveform identically.
                    dispatchListener(generation, listener) { onRmsDb(level * 12f) }
                }

                override fun onVoiceEnd(wav: ByteArray, reason: SegmentEndReason, spokenSeconds: Float) {
                    if (!isCurrent(generation)) return

                    // [T-android-vad-merge-segments] ACCUMULATE, don't discard.
                    //
                    // iOS holds each silence-closed segment in `pendingSegments`
                    // and tests the 2 s minimum against the RUNNING TOTAL
                    // (VoiceInputPanel.swift:635-663). Android used to drop every
                    // sub-2s segment on its own, so natural stop-start speech —
                    // "yes" (0.8 s), pause, "send it" (0.9 s) — could NEVER be
                    // dictated: each piece was binned and the mic switched off.
                    // The 2 s floor still does its job (one isolated cough is
                    // still one short segment), it just applies to the whole
                    // held utterance now.
                    val heldSeconds = synchronized(session.lock) {
                        if (session.terminalClaimed) return
                        session.pendingSegments.add(wav)
                        WavSegmentMerger.totalSeconds(session.pendingSegments)
                    }

                    if (reason == SegmentEndReason.SILENCE_DETECTED &&
                        heldSeconds < MIN_SEGMENT_SECONDS
                    ) {
                        Log.i(
                            TAG,
                            "[vad] holding ${"%.2f".format(spokenSeconds)}s segment " +
                                "(total ${"%.2f".format(heldSeconds)}s < ${MIN_SEGMENT_SECONDS}s)",
                        )
                        // Keep the mic OPEN so the next burst can top it up.
                        // Force-flush after HOLD_FLUSH_MS of real silence so a
                        // genuine cough still resolves (to a "too short" toast)
                        // instead of leaving the mic on indefinitely.
                        val flushJob = scope.launch {
                            kotlinx.coroutines.delay(HOLD_FLUSH_MS)
                            if (!isCurrent(generation)) return@launch
                            val held = synchronized(session.lock) {
                                if (session.terminalClaimed) return@launch
                                WavSegmentMerger.totalSeconds(session.pendingSegments)
                            }
                            Log.i(TAG, "[vad] hold expired at ${"%.2f".format(held)}s — settling")
                            if (!claimVadSessionWithoutAudio(session)) return@launch
                            detachVadSession(session)
                            stopVad()
                            if (held > TOO_SHORT_TOAST_FLOOR) {
                                dispatchListener(generation, listener) {
                                    onError(
                                        RecognitionError.NO_MATCH,
                                        "Too short — hold the mic and speak.",
                                    )
                                }
                            } else {
                                // Silent/accidental taps still need a terminal
                                // callback so the manager can leave FINISHING.
                                dispatchListener(generation, listener) { onFinal("") }
                            }
                        }
                        val previous = synchronized(session.lock) {
                            if (session.terminalClaimed) {
                                flushJob.cancel()
                                null
                            } else {
                                session.holdFlushJob.also { session.holdFlushJob = flushJob }
                            }
                        }
                        previous?.cancel()
                        return
                    }

                    val (merged, segmentCount) = synchronized(session.lock) {
                        if (session.terminalClaimed) return
                        session.terminalClaimed = true
                        session.holdFlushJob?.cancel()
                        session.holdFlushJob = null
                        val count = session.pendingSegments.size
                        val audio = WavSegmentMerger.merge(session.pendingSegments) ?: wav
                        session.pendingSegments.clear()
                        session.rawPcm.reset()
                        audio to count
                    }
                    if (segmentCount > 1) {
                        Log.i(
                            TAG,
                            "[vad] merged $segmentCount segments → " +
                                "${"%.2f".format(heldSeconds)}s",
                        )
                    }
                    // One engine session has one terminal result. Stop capture
                    // for both silence and a length cap before transcribing.
                    detachVadSession(session)
                    stopVad()
                    launchVadTranscription(session, merged)
                }

                override fun onSessionLimit(limit: SessionLimit) {
                    if (!isCurrent(generation)) return
                    Log.i(TAG, "[vad] session limit $limit — finalising")
                    finishVadSession(
                        session,
                        transcribeWithoutDetectedVoice = limit != SessionLimit.IDLE,
                    )
                }

                override fun onCaptureError(message: String) {
                    if (!isCurrent(generation)) return
                    if (!claimVadSessionWithoutAudio(session)) return
                    detachVadSession(session)
                    stopVad()
                    dispatchListener(generation, listener) {
                        onError(RecognitionError.AUDIO_ERROR, message)
                    }
                }
            },
        ).also {
            // iOS splits this by engine: 59 s for Apple's system ASR (which
            // rejects >60 s per request) and the full 300 s session cap for
            // cloud providers, which have no such per-request ceiling
            // (VoiceInputPanel.swift:488-489). This IS the cloud path.
            it.maxSegmentSeconds = CLOUD_MAX_SEGMENT_SECONDS
        }

        // The VAD library cannot flush an in-progress segment on stop. Keep a
        // raw copy so the user's stop tap and session limits can still submit
        // exactly one complete take rather than stranding FINISHING forever.
        det.rawAudioSink = sink@{ bytes, len ->
            if (!isCurrent(generation)) return@sink
            synchronized(session.lock) {
                if (!session.terminalClaimed) session.rawPcm.write(bytes, 0, len)
            }
        }

        detector = det
        val err = det.start()
        if (err != null) {
            val claimed = claimVadSessionWithoutAudio(session)
            detachVadSession(session)
            stopVad()
            if (claimed) {
                dispatchListener(generation, listener) {
                    onError(RecognitionError.AUDIO_ERROR, err)
                }
            }
            return
        }
        if (!isCurrent(generation) || synchronized(session.lock) { session.terminalClaimed }) {
            stopVad()
            return
        }
        dispatchListener(generation, listener) { onReadyForSpeech() }
    }

    private fun detachVadSession(session: VadCaptureSession) {
        if (vadSession === session) vadSession = null
    }

    /** Claim a VAD session for a non-transcribing terminal path. */
    private fun claimVadSessionWithoutAudio(session: VadCaptureSession): Boolean =
        synchronized(session.lock) {
            if (session.terminalClaimed) {
                false
            } else {
                session.terminalClaimed = true
                session.holdFlushJob?.cancel()
                session.holdFlushJob = null
                session.pendingSegments.clear()
                session.rawPcm.reset()
                true
            }
        }

    /** Finalise a manual stop/session limit from the raw capture fallback. */
    private fun finishVadSession(
        session: VadCaptureSession,
        transcribeWithoutDetectedVoice: Boolean = true,
    ) {
        if (!isCurrent(session.generation)) return
        val claimed = synchronized(session.lock) {
            if (session.terminalClaimed) {
                null
            } else {
                session.terminalClaimed = true
                session.holdFlushJob?.cancel()
                session.holdFlushJob = null
                val value = session.rawPcm.toByteArray() to session.sawVoice
                session.rawPcm.reset()
                session.pendingSegments.clear()
                value
            }
        } ?: return

        detachVadSession(session)
        stopVad()
        if (!isCurrent(session.generation)) return

        val (pcm, sawVoice) = claimed
        if (pcm.isEmpty() || (!sawVoice && !transcribeWithoutDetectedVoice)) {
            dispatchListener(session.generation, session.listener) { onFinal("") }
            return
        }
        val wav = VoiceProvider.wrapPcm16InWav(pcm, VoiceActivityDetector.SAMPLE_RATE)
        launchVadTranscription(session, wav)
    }

    private fun launchVadTranscription(session: VadCaptureSession, wav: ByteArray) {
        if (!isCurrent(session.generation)) return
        transcribeJob = scope.launch {
            transcribeSegment(
                wav,
                session.locale,
                session.repo,
                session.candidates,
                session.listener,
                session.generation,
            )
        }
    }

    /** Transcribe one segment. Extracted so the VAD and legacy paths share it. */
    private suspend fun transcribeSegment(
        wav: ByteArray,
        locale: Locale,
        repo: com.openminis.app.data.repository.ProviderRepository,
        candidates: List<Pair<com.openminis.app.data.model.ProviderInstance, com.openminis.app.data.model.ModelEntry>>,
        listener: SpeechRecognitionEngine.Listener,
        generation: Long,
    ) {
        val ordered = stickyEntryId
            ?.let { sticky -> candidates.indexOfFirst { it.second.id == sticky } }
            ?.takeIf { it > 0 }
            ?.let { i -> candidates.drop(i) + candidates.take(i) }
            ?: candidates
        var lastError: Exception? = null
        for ((instance, entry) in ordered) {
            if (!isCurrent(generation)) return
            val provider = VoiceProviderFactory.make(instance, repo.loadApiKey(instance.id))
            if (provider == null) {
                Log.w(TAG, "candidate ${instance.label} cannot serve voice input — skipping")
                continue
            }
            try {
                val response = provider.transcribe(
                    VoiceInputRequest(
                        audioData = wav,
                        model = entry.baseModel.id,
                        language = locale.toLanguageTag(),
                        resolvedModel = entry.model,
                    ),
                )
                if (!isCurrent(generation)) return
                stickyEntryId = entry.id
                val text = response.text.trim()
                if (text.isEmpty()) {
                    dispatchListener(generation, listener) {
                        onError(RecognitionError.NO_MATCH, "Empty transcription.")
                    }
                } else {
                    dispatchListener(generation, listener) { onFinal(text) }
                }
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "transcribe failed on ${entry.model.displayName}: ${e.message} — trying next candidate")
                lastError = e
            }
        }
        if (isCurrent(generation)) {
            val e = lastError
            val kind = when {
                e is VoiceProviderException.Auth -> RecognitionError.PERMISSION_DENIED
                e is java.io.IOException -> RecognitionError.NETWORK
                else -> RecognitionError.UNKNOWN
            }
            dispatchListener(generation, listener) {
                onError(kind, e?.message ?: "All voice-input candidates failed.")
            }
        }
    }

    private fun stopVad() {
        detector?.let {
            it.rawAudioSink = null
            runCatching { it.stop() }
        }
        detector = null
        recording.set(false)
    }

    /** [T-android-vad] See SystemSpeechRecognitionEngine.setBackgrounded. */
    fun setBackgrounded(backgrounded: Boolean) {
        detector?.isBackgrounded = backgrounded
    }

    override fun stop() {
        // VoiceActivityDetector.stop() deliberately emits no in-progress
        // segment. Finalise from our raw capture copy so a stop tap always
        // produces onFinal/onError and the manager can leave FINISHING.
        vadSession?.let {
            finishVadSession(it, transcribeWithoutDetectedVoice = true)
            return
        }
        // Legacy capture loop: flipping this flag hands its PCM take to the
        // existing transcription path.
        recording.set(false)
    }

    override fun cancel() {
        cancelled.set(true)
        callbackGeneration.incrementAndGet()
        val session = vadSession
        vadSession = null
        session?.let {
            synchronized(it.lock) {
                it.terminalClaimed = true
                it.holdFlushJob?.cancel()
                it.holdFlushJob = null
                it.pendingSegments.clear()
                it.rawPcm.reset()
            }
        }
        stopVad()
        captureJob?.cancel()
        captureJob = null
        transcribeJob?.cancel()
        transcribeJob = null
    }

    /** Rough dB estimate over the chunk for the UI waveform. */
    private fun rmsDb(buf: ByteArray, len: Int): Float {
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < len) {
            val sample = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
            sum += sample.toDouble() * sample
            count++
            i += 2
        }
        if (count == 0) return 0f
        val rms = sqrt(sum / count)
        if (rms <= 1.0) return 0f
        // Map amplitude RMS onto the [0, 12]-ish scale the System engine's
        // onRmsChanged reports, so the shared normalizer behaves identically.
        return (20 * log10(rms / 32768.0) + 50).toFloat().coerceIn(0f, 12f)
    }
}
