package com.xnotes.platform

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import com.xnotes.core.model.Stroke

/**
 * Handwriting-to-text via ML Kit Digital Ink Recognition. On-device, offline once the language
 * model is downloaded (a one-time network fetch of a few MB per language). The recognizer consumes
 * strokes directly, so a note's vector ink maps straight onto [Ink] with no rasterization.
 *
 * Every call here blocks on the ML Kit task; run them off the main thread.
 */
object HandwritingOcr {

    /** Thrown when the requested BCP-47 tag has no ML Kit model. */
    class UnsupportedLanguage(tag: String) : Exception("No handwriting model for language '$tag'")

    private val recognizers = HashMap<String, DigitalInkRecognizer>()

    private fun modelFor(tag: String): DigitalInkRecognitionModel {
        val id = DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag)
            ?: throw UnsupportedLanguage(tag)
        return DigitalInkRecognitionModel.builder(id).build()
    }

    // Synchronized: recognize() runs on Dispatchers.IO, so concurrent converts can race on the map.
    @Synchronized
    private fun recognizerFor(tag: String): DigitalInkRecognizer = recognizers.getOrPut(tag) {
        DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(modelFor(tag)).build())
    }

    /** Download the [tag] model if it is not already present. Blocks; call off the main thread. */
    fun ensureModelDownloaded(tag: String) {
        val model = modelFor(tag)
        val manager = RemoteModelManager.getInstance()
        if (Tasks.await(manager.isModelDownloaded(model))) return
        Tasks.await(manager.download(model, DownloadConditions.Builder().build()))
    }

    /**
     * Recognize [strokes] as text in language [tag]. Downloads the model on first use. Returns the
     * top candidate, or an empty string when nothing was recognized. Blocks; call off the main thread.
     */
    fun recognize(strokes: List<Stroke>, tag: String): String {
        val ink = buildInk(strokes) ?: return ""
        ensureModelDownloaded(tag)
        val result = Tasks.await(recognizerFor(tag).recognize(ink))
        return result.candidates.firstOrNull()?.text.orEmpty()
    }

    /** Build an [Ink] from stroke samples. Timestamps are omitted: stored ink has no reliable
     *  cross-stroke timing, and ML Kit treats them only as a hint. */
    private fun buildInk(strokes: List<Stroke>): Ink? {
        val ink = Ink.builder()
        var any = false
        for (s in strokes) {
            val n = s.sampleCount
            if (n == 0) continue
            val stroke = Ink.Stroke.builder()
            for (i in 0 until n) {
                stroke.addPoint(Ink.Point.create(s.xAt(i).toFloat(), s.yAt(i).toFloat()))
            }
            ink.addStroke(stroke.build())
            any = true
        }
        return if (any) ink.build() else null
    }
}
