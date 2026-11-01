package com.eillance.screentranslate.pipeline

import android.os.SystemClock
import android.util.Log
import com.eillance.screentranslate.langid.FastTextClassifier
import com.eillance.screentranslate.langid.LanguageDetector
import com.eillance.screentranslate.langid.ScriptDetector
import com.eillance.screentranslate.models.ModelRepository
import com.eillance.screentranslate.models.StaticCatalog
import com.eillance.screentranslate.ocr.Frame
import com.eillance.screentranslate.ocr.LayoutAnalyzer
import com.eillance.screentranslate.ocr.OcrOptions
import com.eillance.screentranslate.ocr.PaddleOcr
import com.eillance.screentranslate.ocr.TextBlock
import com.eillance.screentranslate.settings.AppSettings
import com.eillance.screentranslate.settings.SettingsStore
import com.eillance.screentranslate.translate.BergamotTranslator
import com.eillance.screentranslate.translate.MissingModelException
import com.eillance.screentranslate.translate.RoutePlanner
import java.io.Closeable

data class TranslatedBlock(
  val block: TextBlock,
  /** Detected (or fallback) source language; null when the block has no letters. */
  val sourceLanguage: String?,
  /** Null when not translated (already in the target language, or model missing). */
  val translation: String?,
)

data class PipelineResult(
  val frameWidth: Int,
  val frameHeight: Int,
  val blocks: List<TranslatedBlock>,
  /** Directions that would have been needed but are not installed, e.g. ("ko", "en"). */
  val missingModels: Set<Pair<String, String>>,
  val timings: Timings,
) {
  data class Timings(val ocrMs: Long, val langIdMs: Long, val translateMs: Long)
}

class NotReadyException(message: String) : Exception(message)

/**
 * Frame → OCR → layout → language detection → translation. Engines are created lazily and
 * rebuilt when the relevant settings or installed models change. Not thread-safe: run it on a
 * single worker thread.
 */
class TranslationPipeline(
  private val models: ModelRepository,
  private val settings: SettingsStore,
) : Closeable {

  private var ocr: PaddleOcr? = null
  private var ocrSignature: String? = null
  private var classifier: FastTextClassifier? = null
  private var translator: BergamotTranslator? = null
  private var translatorThreads = -1

  /** Kotlin-side cache so unchanged blocks (continuous mode) skip JNI entirely. */
  private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > 2000
  }

  fun process(frame: Frame): PipelineResult {
    val s = settings.current
    val ocr = ensureOcr(s)

    val t0 = SystemClock.elapsedRealtime()
    val blocks = LayoutAnalyzer.group(ocr.recognize(frame))
    val t1 = SystemClock.elapsedRealtime()

    val texts = blocks.map { it.text }
    val languages: List<String?> = if (s.autoDetect) {
      LanguageDetector(ensureClassifier()).detectAll(texts, s.fallbackSourceLanguage)
    } else {
      texts.map { if (ScriptDetector.analyze(it).letters == 0) null else s.fallbackSourceLanguage }
    }
    val t2 = SystemClock.elapsedRealtime()

    val translations = arrayOfNulls<String>(blocks.size)
    val missing = HashSet<Pair<String, String>>()
    val byLanguage = blocks.indices.filter { languages[it] != null && languages[it] != s.targetLanguage }
      .groupBy { languages[it]!! }

    for ((lang, indices) in byLanguage) {
      val todo = indices.filter { cache["$lang>${s.targetLanguage}|${texts[it]}"]?.also { hit -> translations[it] = hit } == null }
      if (todo.isEmpty()) continue
      try {
        val route = RoutePlanner.plan(lang, s.targetLanguage, models::installedTranslation)
        val out = ensureTranslator(s).translate(route, todo.map { texts[it] })
        todo.forEachIndexed { k, i ->
          translations[i] = out[k]
          cache["$lang>${s.targetLanguage}|${texts[i]}"] = out[k]
        }
      } catch (e: MissingModelException) {
        missing += e.missing
      }
    }
    val t3 = SystemClock.elapsedRealtime()
    Log.d(TAG, "blocks=${blocks.size} ocr=${t1 - t0}ms lid=${t2 - t1}ms mt=${t3 - t2}ms")

    return PipelineResult(
      frameWidth = frame.width,
      frameHeight = frame.height,
      blocks = blocks.mapIndexed { i, b -> TranslatedBlock(b, languages[i], translations[i]) },
      missingModels = missing,
      timings = PipelineResult.Timings(t1 - t0, t2 - t1, t3 - t2),
    )
  }

  private fun ensureOcr(s: AppSettings): PaddleOcr {
    val detectorId = s.detectorPackId.takeIf(models::isInstalled) ?: StaticCatalog.DETECTOR_DEFAULT
    if (!models.isInstalled(detectorId)) throw NotReadyException("Text detector model is not installed")
    // Core recognizer first, add-ons after.
    val recognizers = models.installedRecognizers().sortedBy { if (it.id == StaticCatalog.RECOGNIZER_CORE) 0 else 1 }
    if (recognizers.none { it.id == StaticCatalog.RECOGNIZER_CORE }) {
      throw NotReadyException("Core text recognizer model is not installed")
    }
    val signature = "$detectorId|${s.detectorMaxSide}|" + recognizers.joinToString(",") { it.id }
    ocr?.let { if (signature == ocrSignature) return it }
    ocr?.close()
    return PaddleOcr(
      models.packDir(detectorId),
      recognizers.map { it to models.packDir(it.id) },
      OcrOptions(detectorMaxSide = s.detectorMaxSide),
    ).also {
      ocr = it
      ocrSignature = signature
    }
  }

  private fun ensureClassifier(): FastTextClassifier? {
    classifier?.let { return it }
    if (!models.isInstalled(StaticCatalog.LANGUAGE_ID)) return null
    val file = models.packDir(StaticCatalog.LANGUAGE_ID).resolve(StaticCatalog.languageId.files.first().fileName)
    return FastTextClassifier.load(file).also { classifier = it }
  }

  private fun ensureTranslator(s: AppSettings): BergamotTranslator {
    translator?.let { if (translatorThreads == s.translationThreads) return it }
    translator?.close()
    return BergamotTranslator(workers = s.translationThreads).also {
      translator = it
      translatorThreads = s.translationThreads
    }
  }

  /** Drops engines so the next [process] picks up newly installed or removed models. */
  fun invalidate() {
    ocr?.close(); ocr = null; ocrSignature = null
    classifier?.close(); classifier = null
  }

  override fun close() {
    invalidate()
    translator?.close(); translator = null
  }

  private companion object {
    const val TAG = "Pipeline"
  }
}
