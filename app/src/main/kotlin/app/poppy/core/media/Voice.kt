package app.poppy.core.media

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** Result of a finished recording, ready for `sendVoiceMessage`. */
data class VoiceClip(val file: File, val mime: String, val durationMs: Long, val waveform: List<Float>)

/**
 * Records voice messages with the platform MediaRecorder: Opus in Ogg (what other Matrix clients
 * expect) on Android 10+, AAC in MP4 before that. Samples amplitude every 100 ms for the waveform.
 */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private val samples = ArrayList<Float>()
    private var sampler: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _level = MutableStateFlow<List<Float>>(emptyList())
    /** Recent amplitude samples (0..1) for the live waveform. */
    val level: StateFlow<List<Float>> = _level

    private val _elapsed = MutableStateFlow(0L)
    val elapsed: StateFlow<Long> = _elapsed

    val isRecording: Boolean get() = recorder != null

    fun start() {
        stopInternal()
        val ogg = Build.VERSION.SDK_INT >= 29
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val out = File(dir, "voice-${System.currentTimeMillis()}.${if (ogg) "ogg" else "m4a"}")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        if (ogg) {
            r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            r.setAudioSamplingRate(48_000)
            r.setAudioEncodingBitRate(32_000)
        } else {
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(64_000)
        }
        r.setAudioChannels(1)
        r.setOutputFile(out.absolutePath)
        r.prepare()
        r.start()
        recorder = r
        file = out
        startedAt = System.currentTimeMillis()
        samples.clear()
        sampler = scope.launch {
            while (isActive) {
                val amp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                // Perceptual-ish scaling so quiet speech is still visible.
                val v = kotlin.math.sqrt(amp / 32767f).coerceIn(0f, 1f)
                samples += v
                _level.value = samples.takeLast(48)
                _elapsed.value = System.currentTimeMillis() - startedAt
                delay(100)
            }
        }
    }

    /** Stops and returns the clip, or null if it was too short to be meaningful. */
    fun stop(): VoiceClip? {
        val r = recorder ?: return null
        val f = file
        val duration = System.currentTimeMillis() - startedAt
        sampler?.cancel()
        recorder = null
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        if (!ok || f == null || duration < 700) {
            f?.delete()
            return null
        }
        return VoiceClip(f, if (f.extension == "ogg") "audio/ogg" else "audio/mp4", duration, downsample(samples, 100))
    }

    fun cancel() {
        stopInternal()
        file?.delete()
        file = null
    }

    private fun stopInternal() {
        sampler?.cancel()
        recorder?.let { runCatching { it.stop() }; it.release() }
        recorder = null
        _level.value = emptyList()
        _elapsed.value = 0
    }

    private fun downsample(src: List<Float>, n: Int): List<Float> {
        if (src.size <= n) return src
        val bucket = src.size.toFloat() / n
        return List(n) { i ->
            val from = (i * bucket).toInt()
            val to = ((i + 1) * bucket).toInt().coerceAtMost(src.size).coerceAtLeast(from + 1)
            src.subList(from, to).max()
        }
    }
}

/** One shared player so starting a voice message stops the previous one. */
object VoicePlayer {
    data class State(val key: String? = null, val playing: Boolean = false, val positionMs: Long = 0, val durationMs: Long = 0)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var player: MediaPlayer? = null
    private var ticker: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun toggle(key: String, file: File) {
        val s = _state.value
        val p = player
        if (s.key == key && p != null) {
            if (p.isPlaying) {
                p.pause()
                _state.value = s.copy(playing = false)
            } else {
                p.start()
                _state.value = s.copy(playing = true)
                tick()
            }
            return
        }
        stop()
        val mp = MediaPlayer()
        mp.setDataSource(file.absolutePath)
        mp.setOnCompletionListener { stop() }
        mp.prepare()
        mp.start()
        player = mp
        _state.value = State(key, true, 0, mp.duration.toLong())
        tick()
    }

    fun seek(key: String, fraction: Float) {
        val p = player ?: return
        if (_state.value.key != key) return
        p.seekTo((p.duration * fraction).toInt())
        _state.value = _state.value.copy(positionMs = p.currentPosition.toLong())
    }

    fun stop() {
        ticker?.cancel()
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        _state.value = State()
    }

    private fun tick() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val p = player ?: break
                _state.value = _state.value.copy(positionMs = p.currentPosition.toLong(), playing = p.isPlaying)
                delay(100)
            }
        }
    }
}
