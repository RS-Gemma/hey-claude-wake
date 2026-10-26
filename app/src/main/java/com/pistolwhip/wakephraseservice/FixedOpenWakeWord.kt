package com.pistolwhip.wakephraseservice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * App-local patched copy of the upstream openWakeWord Android detector.
 * Fix: discard the three mel frames corresponding to the 480-sample overlap.
 * Upstream reference: https://github.com/msnilsen/openwakeword-android
 *
 * Kotlin/Android port of [openWakeWord](https://github.com/dscripka/openWakeWord).
 *
 * Runs the full 3-stage ONNX pipeline (mel spectrogram → speech embedding → wake word
 * classifier) on-device using ONNX Runtime. No network or API key required.
 *
 * Usage:
 * ```
 * val detector = FixedOpenWakeWord.Builder(context)
 *     .setModel(FixedOpenWakeWord.BuiltInModel.HEY_JARVIS)
 *     .setThreshold(0.5f)
 *     .build()
 *
 * detector.start { score -> Log.d("WW", "Detected! score=$score") }
 * // ...later...
 * detector.stop()
 * detector.release()
 * ```
 */
class FixedOpenWakeWord private constructor(
    private val context: Context,
    private val wakeWordModelSource: ModelSource,
    private val threshold: Float,
    private val debounceMs: Long,
    private val statusListener: ((String, Boolean) -> Unit)?,
) {

    /** Pre-trained wake word models bundled with the library. */
    enum class BuiltInModel(internal val assetPath: String, val displayName: String) {
        HEY_JARVIS("openwakeword/hey_jarvis_v0.1.onnx", "Hey Jarvis"),
        ALEXA("openwakeword/alexa_v0.1.onnx", "Alexa"),
        HEY_MYCROFT("openwakeword/hey_mycroft_v0.1.onnx", "Hey Mycroft"),
    }

    /** Callback delivered on the main thread when the wake word is detected. */
    fun interface OnDetectionListener {
        fun onDetected(score: Float)
    }

    // ------------------------------------------------------------------
    // Builder
    // ------------------------------------------------------------------

    class Builder(private val context: Context) {
        private var modelSource: ModelSource = ModelSource.BuiltIn(BuiltInModel.HEY_JARVIS)
        private var threshold = 0.5f
        private var debounceMs = 2000L
        private var statusListener: ((String, Boolean) -> Unit)? = null

        /** Use one of the bundled pre-trained models. */
        fun setModel(model: BuiltInModel) = apply {
            modelSource = ModelSource.BuiltIn(model)
        }

        /** Use a custom ONNX model from the app's assets folder. */
        fun setModelAsset(assetPath: String) = apply {
            modelSource = ModelSource.Asset(assetPath)
        }

        /** Use a custom ONNX model loaded as raw bytes. */
        fun setModelBytes(bytes: ByteArray) = apply {
            modelSource = ModelSource.Raw(bytes)
        }

        /**
         * Detection threshold (0.0 – 1.0).
         * Lower = more sensitive (more false positives).
         * Higher = stricter (may miss quiet speech).
         * Default: 0.5
         */
        fun setThreshold(threshold: Float) = apply {
            this.threshold = threshold.coerceIn(0.01f, 0.99f)
        }

        /**
         * Minimum time between consecutive detections, in milliseconds.
         * Default: 2000
         */
        fun setDebounceMs(ms: Long) = apply {
            this.debounceMs = ms.coerceAtLeast(0)
        }

        /** Receives detector diagnostics and whether listening is currently healthy. */
        fun setStatusListener(listener: (String, Boolean) -> Unit) = apply {
            statusListener = listener
        }

        fun build(): FixedOpenWakeWord = FixedOpenWakeWord(
            context.applicationContext,
            modelSource,
            threshold,
            debounceMs,
            statusListener,
        )
    }

    // ------------------------------------------------------------------
    // Internal types
    // ------------------------------------------------------------------

    internal sealed class ModelSource {
        data class BuiltIn(val model: BuiltInModel) : ModelSource()
        data class Asset(val path: String) : ModelSource()
        data class Raw(val bytes: ByteArray) : ModelSource()
    }

    // ------------------------------------------------------------------
    // Constants
    // ------------------------------------------------------------------

    companion object {
        private const val TAG = "FixedOpenWakeWord"
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SAMPLES = 1280 // 80 ms
        private const val MEL_CONTEXT_SAMPLES = 480 // 160 * 3 overlap
        private const val MEL_CONTEXT_FRAMES = 3 // Ignore frames already represented by the prior 80 ms audio chunk
        private const val MEL_BINS = 32
        private const val MEL_WINDOW_FRAMES = 76
        private const val EMBEDDING_DIM = 96
        private const val FEATURE_WINDOW = 16
        private const val FEATURE_BUFFER_MAX = 120
        private const val MEL_BUFFER_MAX = 970
        private const val SKIP_INITIAL_PREDICTIONS = 5
        private const val RAW_BUFFER_SECONDS = 10
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    private var ortEnv: OrtEnvironment? = null
    private var melSpecSession: OrtSession? = null
    private var embeddingSession: OrtSession? = null
    private var wakeWordSession: OrtSession? = null
    private var wakeWordInputName: String? = null

    private var audioRecord: AudioRecord? = null
    private var processingThread: Thread? = null
    @Volatile private var isRunning = false

    private var listener: OnDetectionListener? = null
    private var lastDetectionTime = 0L
    private var predictionCount = 0
    private var capturedFrameCount = 0L
    private var lastReportedScore = 0f

    private lateinit var rawBuffer: FloatArray
    private var rawWritePos = 0
    private var rawTotalWritten = 0L

    private val melBuffer = ArrayList<FloatArray>()
    private val featureBuffer = ArrayList<FloatArray>()

    private val mainHandler = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Start listening for the wake word.
     * The [listener] is called on the **main thread** each time the wake word is detected.
     * Requires `RECORD_AUDIO` permission.
     */
    fun start(listener: OnDetectionListener) {
        if (isRunning) return
        this.listener = listener
        isRunning = true

        processingThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            try {
                reportStatus("Loading ONNX wake-word models…", true)
                if (melSpecSession == null) initModels()
                initBuffers()
                reportStatus("ONNX models loaded; initializing microphone…", true)
                if (!initAudioRecord()) {
                    throw IllegalStateException("AudioRecord failed to initialize. Check microphone permission and whether another app is using the microphone.")
                }
                audioLoop()
            } catch (e: Exception) {
                isRunning = false
                Log.e(TAG, "Error in processing thread", e)
                reportStatus("Detector failed: ${e.javaClass.simpleName}: ${e.message ?: "no details"}", false)
            } finally {
                releaseAudioRecord()
                if (isRunning) {
                    isRunning = false
                    reportStatus("Detector stopped unexpectedly; microphone released.", false)
                }
            }
        }, "FixedOpenWakeWord")
        processingThread?.start()
    }

    /** Stop listening. Can be restarted with [start]. */
    fun stop() {
        isRunning = false
        processingThread?.join(3000)
        processingThread = null
        releaseAudioRecord()
    }

    /** Release all resources. Instance cannot be reused after this. */
    fun release() {
        stop()
        try {
            wakeWordSession?.close()
            embeddingSession?.close()
            melSpecSession?.close()
            ortEnv?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing ONNX sessions", e)
        }
        wakeWordSession = null
        embeddingSession = null
        melSpecSession = null
        ortEnv = null
        listener = null
    }

    // ------------------------------------------------------------------
    // Model loading
    // ------------------------------------------------------------------

    private fun initModels() {
        ortEnv = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
        }

        melSpecSession = ortEnv!!.createSession(
            loadAsset("openwakeword/melspectrogram.onnx"), opts
        )
        embeddingSession = ortEnv!!.createSession(
            loadAsset("openwakeword/embedding_model.onnx"), opts
        )

        val modelBytes = when (val src = wakeWordModelSource) {
            is ModelSource.BuiltIn -> loadAsset(src.model.assetPath)
            is ModelSource.Asset -> loadAsset(src.path)
            is ModelSource.Raw -> src.bytes
        }
        wakeWordSession = ortEnv!!.createSession(modelBytes, opts)
        wakeWordInputName = wakeWordSession!!.inputNames.first()
        Log.d(TAG, "Models loaded. Input name: $wakeWordInputName")
    }

    private fun loadAsset(name: String): ByteArray =
        context.assets.open(name).use { it.readBytes() }

    // ------------------------------------------------------------------
    // Buffers
    // ------------------------------------------------------------------

    private fun initBuffers() {
        rawBuffer = FloatArray(SAMPLE_RATE * RAW_BUFFER_SECONDS)
        rawWritePos = 0
        rawTotalWritten = 0L

        melBuffer.clear()
        repeat(MEL_WINDOW_FRAMES) { melBuffer.add(FloatArray(MEL_BINS) { 1.0f }) }

        featureBuffer.clear()
        repeat(FEATURE_WINDOW) { featureBuffer.add(FloatArray(EMBEDDING_DIM)) }

        predictionCount = 0
        capturedFrameCount = 0L
        lastReportedScore = 0f
        lastDetectionTime = 0L
    }

    // ------------------------------------------------------------------
    // Audio capture
    // ------------------------------------------------------------------

    private fun initAudioRecord(): Boolean {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) return false

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) return false

        return try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize.coerceAtLeast(FRAME_SAMPLES * 4)
            )
            audioRecord?.state == AudioRecord.STATE_INITIALIZED
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioRecord", e)
            false
        }
    }

    private fun releaseAudioRecord() {
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }

    // ------------------------------------------------------------------
    // Main processing loop
    // ------------------------------------------------------------------

    private fun audioLoop() {
        val recorder = audioRecord ?: throw IllegalStateException("AudioRecord is missing.")
        recorder.startRecording()
        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw IllegalStateException("AudioRecord did not enter recording state.")
        }
        reportStatus("Microphone recording confirmed; collecting audio…", true)
        val frame = ShortArray(FRAME_SAMPLES)

        while (isRunning) {
            val read = audioRecord?.read(frame, 0, FRAME_SAMPLES) ?: break
            if (read < 0) throw IllegalStateException("AudioRecord read failed (code ${read}).")
            if (read != FRAME_SAMPLES) continue
            capturedFrameCount++
            if (capturedFrameCount % 25L == 0L) reportStatus(diagnosticSummary("Audio capture active"), true)

            addToRawBuffer(frame)
            if (rawTotalWritten < FRAME_SAMPLES + MEL_CONTEXT_SAMPLES) continue

            val audioSlice = getLastNSamples(FRAME_SAMPLES + MEL_CONTEXT_SAMPLES)
            val newMelFrames = computeMelSpectrogram(audioSlice) ?: continue

            // Each 1760-sample window overlaps the previous window by 480 samples.
            // The mel model emits frames for that overlap too; append only the new frames.
            for (melFrame in newMelFrames.drop(MEL_CONTEXT_FRAMES)) melBuffer.add(melFrame)
            while (melBuffer.size > MEL_BUFFER_MAX) melBuffer.removeAt(0)

            if (melBuffer.size >= MEL_WINDOW_FRAMES) {
                val start = melBuffer.size - MEL_WINDOW_FRAMES
                val melWindow = ArrayList<FloatArray>(MEL_WINDOW_FRAMES)
                for (i in start until melBuffer.size) melWindow.add(melBuffer[i])

                val embedding = computeEmbedding(melWindow) ?: continue
                featureBuffer.add(embedding)
                while (featureBuffer.size > FEATURE_BUFFER_MAX) featureBuffer.removeAt(0)
            }

            if (featureBuffer.size >= FEATURE_WINDOW) {
                predictionCount++
                if (predictionCount <= SKIP_INITIAL_PREDICTIONS) continue

                val fStart = featureBuffer.size - FEATURE_WINDOW
                val features = ArrayList<FloatArray>(FEATURE_WINDOW)
                for (i in fStart until featureBuffer.size) features.add(featureBuffer[i])

                val score = runWakeWordModel(features)
                lastReportedScore = score
                if (predictionCount % 10 == 0) reportStatus(diagnosticSummary("Audio capture and inference active"), true)
                if (score >= threshold) {
                    val now = System.currentTimeMillis()
                    if (now - lastDetectionTime > debounceMs) {
                        lastDetectionTime = now
                        Log.d(TAG, "Wake word detected! score=$score")
                        val cb = listener
                        mainHandler.post { cb?.onDetected(score) }
                    }
                }
            }
        }
    }

    private fun diagnosticSummary(prefix: String): String =
        "${prefix}; audio frames=${capturedFrameCount}, inferences=${predictionCount}, latest confidence=${String.format(java.util.Locale.US, "%.3f", lastReportedScore)}, threshold=${String.format(java.util.Locale.US, "%.2f", threshold)}."

    private fun reportStatus(message: String, healthy: Boolean) {
        try {
            statusListener?.invoke(message, healthy)
        } catch (error: Exception) {
            Log.w(TAG, "Diagnostic callback failed", error)
        }
    }

    // ------------------------------------------------------------------
    // Ring buffer helpers
    // ------------------------------------------------------------------

    private fun addToRawBuffer(frame: ShortArray) {
        for (sample in frame) {
            rawBuffer[rawWritePos] = sample.toFloat()
            rawWritePos = (rawWritePos + 1) % rawBuffer.size
        }
        rawTotalWritten += frame.size
    }

    private fun getLastNSamples(n: Int): FloatArray {
        val result = FloatArray(n)
        val available = minOf(n, rawBuffer.size, rawTotalWritten.toInt())
        var readPos = (rawWritePos - available + rawBuffer.size) % rawBuffer.size
        for (i in 0 until available) {
            result[n - available + i] = rawBuffer[readPos]
            readPos = (readPos + 1) % rawBuffer.size
        }
        return result
    }

    // ------------------------------------------------------------------
    // ONNX inference
    // ------------------------------------------------------------------

    private fun computeMelSpectrogram(audio: FloatArray): List<FloatArray>? {
        val env = ortEnv ?: return null
        val session = melSpecSession ?: return null
        return try {
            OnnxTensor.createTensor(
                env, FloatBuffer.wrap(audio), longArrayOf(1, audio.size.toLong())
            ).use { input ->
                session.run(mapOf("input" to input)).use { result ->
                    val output = result[0] as OnnxTensor
                    val shape = output.info.shape // [1, 1, T, 32]
                    val flat = output.floatBuffer
                    val numFrames = shape[2].toInt()
                    val numBins = shape[3].toInt()

                    ArrayList<FloatArray>(numFrames).also { frames ->
                        for (f in 0 until numFrames) {
                            val melFrame = FloatArray(numBins)
                            for (b in 0 until numBins) {
                                melFrame[b] = flat.get(f * numBins + b) / 10.0f + 2.0f
                            }
                            frames.add(melFrame)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Mel spectrogram error", e)
            null
        }
    }

    private fun computeEmbedding(melWindow: List<FloatArray>): FloatArray? {
        val env = ortEnv ?: return null
        val session = embeddingSession ?: return null
        return try {
            val flatData = FloatArray(MEL_WINDOW_FRAMES * MEL_BINS)
            for (i in 0 until MEL_WINDOW_FRAMES) {
                System.arraycopy(melWindow[i], 0, flatData, i * MEL_BINS, MEL_BINS)
            }
            OnnxTensor.createTensor(
                env, FloatBuffer.wrap(flatData),
                longArrayOf(1, MEL_WINDOW_FRAMES.toLong(), MEL_BINS.toLong(), 1)
            ).use { input ->
                session.run(mapOf("input_1" to input)).use { result ->
                    val buf = (result[0] as OnnxTensor).floatBuffer
                    FloatArray(EMBEDDING_DIM) { buf.get(it) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Embedding error", e)
            null
        }
    }

    private fun runWakeWordModel(features: List<FloatArray>): Float {
        val env = ortEnv ?: return 0f
        val session = wakeWordSession ?: return 0f
        val inputName = wakeWordInputName ?: return 0f
        return try {
            val flatData = FloatArray(FEATURE_WINDOW * EMBEDDING_DIM)
            for (i in 0 until FEATURE_WINDOW) {
                System.arraycopy(features[i], 0, flatData, i * EMBEDDING_DIM, EMBEDDING_DIM)
            }
            OnnxTensor.createTensor(
                env, FloatBuffer.wrap(flatData),
                longArrayOf(1, FEATURE_WINDOW.toLong(), EMBEDDING_DIM.toLong())
            ).use { input ->
                session.run(mapOf(inputName to input)).use { result ->
                    (result[0] as OnnxTensor).floatBuffer.get(0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Wake word model error", e)
            0f
        }
    }
}
