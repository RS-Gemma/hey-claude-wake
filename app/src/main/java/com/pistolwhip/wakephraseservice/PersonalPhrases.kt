package com.pistolwhip.wakephraseservice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A wake phrase learned from the user's own recordings. Each sample is stored as a sequence of
 * speech embeddings (one per 80 ms); live audio is compared to the samples with dynamic time
 * warping. No training server is involved, so this is tuned to one voice, not to everyone's.
 */
class PersonalPhrase(
    val id: String,
    val label: String,
    var enabled: Boolean = true,
    val templates: MutableList<Array<FloatArray>> = mutableListOf(),
    var threshold: Float = 0f,
    var calibration: String = "",
) {
    val ready: Boolean get() = templates.size >= PersonalPhrases.MIN_SAMPLES && threshold > 0f
}

object PersonalPhrases {
    const val MIN_SAMPLES = 3
    const val MAX_SAMPLES = 8
    const val SAMPLE_FRAMES = 30 // 2.4 s per phrase recording
    const val BACKGROUND_FRAMES = 250 // 20 s of normal talking

    private const val PHRASES_FILE = "personal_phrases.json"
    private const val BACKGROUND_FILE = "background_speech.json"
    private const val MIN_TEMPLATE_FRAMES = 4
    private const val MAX_TEMPLATE_FRAMES = 20
    // Each embedding covers ~0.78 s of audio ending at its frame, so keep a few frames after
    // the speech ends to include the whole phrase.
    private const val TAIL_FRAMES = 4

    private val DEFAULTS = listOf("hey_claude" to "Hey Claude", "hey_buddy" to "Hey buddy")

    fun load(context: Context): MutableList<PersonalPhrase> {
        val stored = readJson(context, PHRASES_FILE)?.let { json ->
            val array = JSONArray(json)
            (0 until array.length()).map { phraseFromJson(array.getJSONObject(it)) }
        }.orEmpty()
        return DEFAULTS.map { (id, label) ->
            stored.firstOrNull { it.id == id } ?: PersonalPhrase(id, label)
        }.toMutableList()
    }

    fun save(context: Context, phrases: List<PersonalPhrase>) {
        val array = JSONArray()
        phrases.forEach { array.put(phraseToJson(it)) }
        File(context.filesDir, PHRASES_FILE).writeText(array.toString())
    }

    fun loadBackground(context: Context): List<FloatArray> =
        readJson(context, BACKGROUND_FILE)?.let { sequenceFromJson(JSONArray(it)) }.orEmpty()

    fun saveBackground(context: Context, frames: List<FloatArray>) {
        File(context.filesDir, BACKGROUND_FILE).writeText(sequenceToJson(frames).toString())
    }

    /**
     * Cuts the spoken phrase out of a recording of (embedding, audio level) frames.
     * Returns null when no clear phrase was heard.
     */
    fun extractTemplate(frames: List<Pair<FloatArray, Float>>): Array<FloatArray>? {
        if (frames.size < MIN_TEMPLATE_FRAMES) return null
        val levels = frames.map { it.second }.sorted()
        val floor = levels[levels.size / 5]
        val peak = levels.last()
        if (peak < floor * 3f || peak < 200f) return null
        val speechLevel = floor + 0.3f * (peak - floor)
        val start = frames.indexOfFirst { it.second >= speechLevel }
        val end = frames.indexOfLast { it.second >= speechLevel }
        val last = min(end + TAIL_FRAMES, frames.size - 1)
        val length = last - start + 1
        if (length < MIN_TEMPLATE_FRAMES || length > MAX_TEMPLATE_FRAMES + TAIL_FRAMES) return null
        return Array(min(length, MAX_TEMPLATE_FRAMES)) { normalize(frames[start + it].first) }
    }

    /**
     * Sets the phrase's threshold from how similar its samples are to each other and how
     * close normal talking comes to them. Lower distance = better match.
     */
    fun calibrate(phrase: PersonalPhrase, background: List<FloatArray>) {
        if (phrase.templates.size < MIN_SAMPLES) {
            phrase.threshold = 0f
            phrase.calibration = "Needs ${MIN_SAMPLES - phrase.templates.size} more sample(s)."
            return
        }
        // Worst case among samples of the best match to any other sample.
        val samplesSpread = phrase.templates.indices.maxOf { i ->
            phrase.templates.indices.filter { it != i }
                .minOf { j -> suffixDistance(phrase.templates[i], phrase.templates[j].asList()) }
        }
        val normalized = background.map { normalize(it) }
        val closestBackground = if (normalized.size > MAX_TEMPLATE_FRAMES) {
            val window = windowFor(phrase)
            (MIN_TEMPLATE_FRAMES until normalized.size).minOf { end ->
                val from = max(0, end - window + 1)
                bestDistance(phrase, normalized.subList(from, end + 1))
            }
        } else {
            Float.NaN
        }
        phrase.threshold = when {
            closestBackground.isNaN() -> samplesSpread * 1.15f
            closestBackground > samplesSpread -> samplesSpread + 0.5f * (closestBackground - samplesSpread)
            else -> closestBackground * 0.95f
        }
        phrase.calibration = buildString {
            append("${phrase.templates.size} samples; samples spread %.3f".format(Locale.US, samplesSpread))
            if (closestBackground.isNaN()) {
                append("; no normal-talking recording yet")
            } else {
                append("; closest normal talking %.3f".format(Locale.US, closestBackground))
                if (closestBackground <= samplesSpread) {
                    append(". WARNING: normal talking sounds too similar; expect misses. Re-record samples more consistently.")
                }
            }
            append("; threshold %.3f".format(Locale.US, phrase.threshold))
        }
    }

    /** Lowest distance of any of the phrase's samples to the end of [recent]. */
    fun bestDistance(phrase: PersonalPhrase, recent: List<FloatArray>): Float =
        phrase.templates.minOf { suffixDistance(it, recent) }

    /** How many recent frames to compare with this phrase: its longest sample plus slack for speed. */
    fun windowFor(phrase: PersonalPhrase): Int =
        ((phrase.templates.maxOfOrNull { it.size } ?: MAX_TEMPLATE_FRAMES) * 1.5f).roundToInt() + 1

    /**
     * Dynamic time warping cost of aligning the whole [template] with a stretch of [sequence]
     * that ends at its last frame (the start is free), divided by the template length.
     * Frames must be normalized; the frame cost is cosine distance.
     */
    fun suffixDistance(template: Array<FloatArray>, sequence: List<FloatArray>): Float {
        val m = sequence.size
        if (m == 0) return Float.MAX_VALUE
        var previous = FloatArray(m) { cosineDistance(template[0], sequence[it]) }
        var current = FloatArray(m)
        for (i in 1 until template.size) {
            current[0] = previous[0] + cosineDistance(template[i], sequence[0])
            for (j in 1 until m) {
                current[j] = cosineDistance(template[i], sequence[j]) +
                    min(previous[j], min(previous[j - 1], current[j - 1]))
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[m - 1] / template.size
    }

    fun normalize(vector: FloatArray): FloatArray {
        var sum = 0.0
        for (v in vector) sum += v.toDouble() * v
        val norm = sqrt(sum).toFloat()
        return if (norm == 0f) vector.copyOf() else FloatArray(vector.size) { vector[it] / norm }
    }

    private fun cosineDistance(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (k in a.indices) dot += a[k] * b[k]
        return 1f - dot
    }

    private fun readJson(context: Context, name: String): String? =
        File(context.filesDir, name).takeIf { it.exists() }?.readText()

    private fun phraseToJson(phrase: PersonalPhrase) = JSONObject().apply {
        put("id", phrase.id)
        put("label", phrase.label)
        put("enabled", phrase.enabled)
        put("threshold", phrase.threshold.toDouble())
        put("calibration", phrase.calibration)
        put("templates", JSONArray().apply { phrase.templates.forEach { put(sequenceToJson(it.asList())) } })
    }

    private fun phraseFromJson(json: JSONObject): PersonalPhrase {
        val templates = json.getJSONArray("templates")
        return PersonalPhrase(
            id = json.getString("id"),
            label = json.getString("label"),
            enabled = json.optBoolean("enabled", true),
            templates = (0 until templates.length())
                .map { sequenceFromJson(templates.getJSONArray(it)).toTypedArray() }
                .toMutableList(),
            threshold = json.optDouble("threshold", 0.0).toFloat(),
            calibration = json.optString("calibration", ""),
        )
    }

    private fun sequenceToJson(frames: List<FloatArray>) = JSONArray().apply {
        frames.forEach { frame -> put(JSONArray().apply { frame.forEach { put(it.toDouble()) } }) }
    }

    private fun sequenceFromJson(array: JSONArray): List<FloatArray> =
        (0 until array.length()).map { i ->
            val frame = array.getJSONArray(i)
            FloatArray(frame.length()) { frame.getDouble(it).toFloat() }
        }
}

/** Matches live embeddings against the enabled personal phrases. Not thread-safe; use from the processing thread. */
class PersonalMatcher(phrases: List<PersonalPhrase>) {
    private val active = phrases.filter { it.enabled && it.ready }
    private val windows = active.associateWith { PersonalPhrases.windowFor(it) }
    private val maxWindow = windows.values.maxOrNull() ?: 0
    private val recent = ArrayList<FloatArray>()

    val isEmpty: Boolean get() = active.isEmpty()

    /** Adds one embedding; returns the phrase heard and its distance, or null. */
    fun process(embedding: FloatArray): Pair<PersonalPhrase, Float>? {
        if (active.isEmpty()) return null
        recent.add(PersonalPhrases.normalize(embedding))
        while (recent.size > maxWindow) recent.removeAt(0)
        for (phrase in active) {
            val window = windows.getValue(phrase)
            if (recent.size < window) continue
            val distance = PersonalPhrases.bestDistance(phrase, recent.subList(recent.size - window, recent.size))
            if (distance <= phrase.threshold) {
                // Start fresh so the same utterance does not trigger again.
                recent.clear()
                return phrase to distance
            }
        }
        return null
    }
}
