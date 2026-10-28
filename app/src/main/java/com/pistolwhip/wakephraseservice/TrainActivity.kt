package com.pistolwhip.wakephraseservice

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Records the user's own wake phrases. The listening service is paused while this screen is
 * open (it needs the microphone) and restarted with the Done button if it was running.
 */
class TrainActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var phrases: MutableList<PersonalPhrase>
    private var backgroundSpeech: List<FloatArray> = emptyList()
    private var recorder: FixedOpenWakeWord? = null
    private var micReady = false
    private var wasListening = false

    @Volatile private var capture: MutableList<Pair<FloatArray, Float>>? = null
    @Volatile private var captureTarget = 0
    private var onCaptureDone: ((List<Pair<FloatArray, Float>>) -> Unit)? = null

    private lateinit var statusView: TextView
    private lateinit var phraseViews: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        phrases = PersonalPhrases.load(this)
        backgroundSpeech = PersonalPhrases.loadBackground(this)

        wasListening = getSharedPreferences(WakeWordService.PREFS_NAME, MODE_PRIVATE)
            .getBoolean(WakeWordService.KEY_LISTENING, false)
        if (wasListening) {
            startService(Intent(this, WakeWordService::class.java).setAction(WakeWordService.ACTION_STOP))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 32, 28, 24)
        }
        root.addView(TextView(this).apply {
            text = "Train personal phrases"
            textSize = 24f
        }, matchWrap())
        root.addView(TextView(this).apply {
            text = "For each phrase, record at least ${PersonalPhrases.MIN_SAMPLES} samples " +
                "(5 is better): tap Record, then say the phrase once, the way you normally would. " +
                "Then record 20 seconds of normal talking (any language, or TV) so the app learns " +
                "what should NOT trigger it. Listening is paused while this screen is open."
            textSize = 14f
            setPadding(0, 16, 0, 16)
        }, matchWrap())

        statusView = TextView(this).apply {
            textSize = 16f
            text = "Starting microphone…"
        }
        root.addView(statusView, matchWrap())

        phraseViews = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(phraseViews, matchWrap())

        root.addView(TextView(this).apply {
            text = "Normal talking"
            textSize = 20f
            setPadding(0, 32, 0, 8)
        }, matchWrap())
        val backgroundInfo = TextView(this).apply { textSize = 13f }
        backgroundInfo.text = backgroundText()
        root.addView(backgroundInfo, matchWrap())
        root.addView(Button(this).apply {
            text = "Record 20 s of normal talking"
            setOnClickListener {
                record(PersonalPhrases.BACKGROUND_FRAMES, "Talk normally for 20 seconds (don't say the wake phrases)…") { frames ->
                    backgroundSpeech = frames.map { it.first }
                    PersonalPhrases.saveBackground(this@TrainActivity, backgroundSpeech)
                    phrases.forEach { PersonalPhrases.calibrate(it, backgroundSpeech) }
                    PersonalPhrases.save(this@TrainActivity, phrases)
                    backgroundInfo.text = backgroundText()
                    statusView.text = "Normal talking recorded; thresholds updated."
                    renderPhrases()
                }
            }
        }, matchWrap())

        root.addView(Button(this).apply {
            text = if (wasListening) "Done (resume listening)" else "Done"
            setOnClickListener { finishAndResume() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 32
        })

        val scroll = ScrollView(this).apply { addView(root) }
        if (Build.VERSION.SDK_INT >= 30) {
            scroll.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        } else {
            scroll.fitsSystemWindows = true
        }
        setContentView(scroll)
        renderPhrases()

        // Give the listening service a moment to release the microphone.
        mainHandler.postDelayed({ startRecorder() }, 800)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finishAndResume()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        recorder?.let { runCatching { it.stop() }; runCatching { it.release() } }
        recorder = null
        super.onDestroy()
    }

    private fun finishAndResume() {
        recorder?.let { runCatching { it.stop() }; runCatching { it.release() } }
        recorder = null
        if (wasListening) {
            val intent = Intent(this, WakeWordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        }
        finish()
    }

    private fun startRecorder() {
        recorder = FixedOpenWakeWord.Builder(this)
            .setModel(FixedOpenWakeWord.BuiltInModel.HEY_JARVIS)
            .setStatusListener { status, healthy ->
                runOnUiThread {
                    if (!healthy) {
                        micReady = false
                        statusView.text = "Microphone problem: $status"
                    } else if (!micReady && status.startsWith("Microphone recording confirmed")) {
                        // Let the detector's audio window fill before the first recording.
                        mainHandler.postDelayed({
                            micReady = true
                            statusView.text = "Ready. Pick a phrase and tap Record."
                        }, 1000)
                    }
                }
            }
            .setEmbeddingListener { embedding, level ->
                val frames = capture ?: return@setEmbeddingListener
                frames.add(embedding to level)
                if (frames.size >= captureTarget) {
                    capture = null
                    runOnUiThread { onCaptureDone?.invoke(frames) }
                }
            }
            .build()
        recorder?.start { }
    }

    private fun record(frames: Int, prompt: String, done: (List<Pair<FloatArray, Float>>) -> Unit) {
        if (!micReady) {
            statusView.text = "Microphone not ready yet; wait a moment."
            return
        }
        if (capture != null) return
        statusView.text = prompt
        onCaptureDone = done
        captureTarget = frames
        capture = ArrayList(frames)
    }

    private fun renderPhrases() {
        phraseViews.removeAllViews()
        for (phrase in phrases) {
            phraseViews.addView(TextView(this).apply {
                text = "“${phrase.label}”"
                textSize = 20f
                setPadding(0, 32, 0, 4)
            }, matchWrap())
            phraseViews.addView(TextView(this).apply {
                textSize = 13f
                text = when {
                    phrase.templates.isEmpty() -> "No samples yet."
                    else -> phrase.calibration
                } + if (phrase.ready && !phrase.enabled) " (turned off)" else ""
            }, matchWrap())
            phraseViews.addView(CheckBox(this).apply {
                text = "Use this phrase"
                isChecked = phrase.enabled
                setOnCheckedChangeListener { _, checked ->
                    phrase.enabled = checked
                    PersonalPhrases.save(this@TrainActivity, phrases)
                }
            }, matchWrap())
            phraseViews.addView(Button(this).apply {
                text = "Record sample (${phrase.templates.size}/${PersonalPhrases.MAX_SAMPLES})"
                isEnabled = phrase.templates.size < PersonalPhrases.MAX_SAMPLES
                setOnClickListener {
                    record(PersonalPhrases.SAMPLE_FRAMES, "Say “${phrase.label}” now…") { frames ->
                        val template = PersonalPhrases.extractTemplate(frames)
                        if (template == null) {
                            statusView.text = "Didn't catch a clear “${phrase.label}”. Tap Record and say it once, right away."
                            return@record
                        }
                        phrase.templates.add(template)
                        PersonalPhrases.calibrate(phrase, backgroundSpeech)
                        PersonalPhrases.save(this@TrainActivity, phrases)
                        statusView.text = "Sample saved for “${phrase.label}”."
                        renderPhrases()
                    }
                }
            }, matchWrap())
            phraseViews.addView(Button(this).apply {
                text = "Delete all samples"
                isEnabled = phrase.templates.isNotEmpty()
                setOnClickListener {
                    phrase.templates.clear()
                    PersonalPhrases.calibrate(phrase, backgroundSpeech)
                    PersonalPhrases.save(this@TrainActivity, phrases)
                    renderPhrases()
                }
            }, matchWrap())
        }
    }

    private fun backgroundText(): String =
        if (backgroundSpeech.isEmpty()) "Not recorded yet." else "Recorded (${backgroundSpeech.size} frames). Record again to replace."

    private fun matchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}
