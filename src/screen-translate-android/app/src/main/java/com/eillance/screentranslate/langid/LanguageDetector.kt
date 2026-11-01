package com.eillance.screentranslate.langid

import com.eillance.screentranslate.lang.Languages
import com.eillance.screentranslate.lang.Script

/** One candidate from a statistical classifier. */
data class LangGuess(val code: String, val probability: Float)

/** Something that can rank languages for a line of text (fastText in production, fakes in tests). */
fun interface LanguageClassifier {
  fun classify(text: String, topK: Int): List<LangGuess>
}

data class Detection(val code: String?, val confident: Boolean)

/**
 * Detects the language of OCR'd text blocks.
 *
 * Strategy, cheapest and most reliable first:
 *  1. Unambiguous scripts decide on their own (kana => Japanese, Hangul => Korean, Thai, Greek...).
 *  2. Shared scripts (Latin, Cyrillic, Arabic, Devanagari, Han-only) go to the fastText classifier.
 *  3. Blocks that are too short or low-confidence inherit the dominant language of the screen,
 *     and finally the user's fallback source language.
 *
 * Han-only text (e.g. a kanji-only Japanese label such as 設定) is the classic trap: if anything else
 * on the screen is Japanese, or the fallback is Japanese, we treat it as Japanese.
 */
class LanguageDetector(private val classifier: LanguageClassifier?) {

  fun detect(text: String): Detection {
    val stats = ScriptDetector.analyze(text)
    if (stats.letters == 0) return Detection(null, confident = false)

    // Any meaningful amount of kana means Japanese; Japanese text is mostly kanji + kana.
    if (stats[Script.KANA] >= 1 && stats.fraction(Script.KANA) >= 0.05f) return Detection("ja", true)

    val unique = when (stats.dominant) {
      Script.HANGUL -> "ko"
      Script.THAI -> "th"
      Script.GREEK -> "el"
      Script.HEBREW -> "he"
      Script.BENGALI -> "bn"
      Script.GUJARATI -> "gu"
      Script.KANNADA -> "kn"
      Script.MALAYALAM -> "ml"
      Script.TAMIL -> "ta"
      Script.TELUGU -> "te"
      else -> null
    }
    if (unique != null) return Detection(unique, confident = stats.letters >= 2)

    val guesses = classifier?.classify(text, topK = 3).orEmpty()
    val best = guesses.firstNotNullOfOrNull { g -> Languages.fromFastText(g.code)?.let { it to g.probability } }

    if (stats.dominant == Script.HAN) {
      // Han-only text. fastText is weak here; only trust a strong "ja".
      val isJa = best?.first == "ja" && best.second >= 0.6f
      if (isJa) return Detection("ja", confident = true)
      val chinese = if (ScriptDetector.looksTraditional(text)) "zh-Hant" else "zh-Hans"
      // Japanese sentences almost always contain kana, so a long Han-only run is Chinese.
      // Short ones (labels like 設定, 名前) are ambiguous: screen context may override.
      return Detection(chinese, confident = stats.letters >= LONG_HAN_RUN)
    }

    if (best == null) return Detection(null, confident = false)
    val code = if (best.first == "zh") {
      if (ScriptDetector.looksTraditional(text)) "zh-Hant" else "zh-Hans"
    } else {
      best.first
    }
    val confident = best.second >= MIN_CONFIDENCE && stats.letters >= MIN_LETTERS
    return Detection(code, confident)
  }

  /**
   * Detects every block, then resolves unconfident ones from screen context.
   * Returns one language code per input (null = no letters, nothing to translate).
   */
  fun detectAll(texts: List<String>, fallback: String): List<String?> {
    val detections = texts.map { detect(it) }

    // Letter-weighted vote among confident detections.
    val votes = HashMap<String, Int>()
    detections.forEachIndexed { i, d ->
      if (d.confident && d.code != null) {
        votes.merge(d.code, ScriptDetector.analyze(texts[i]).letters, Int::plus)
      }
    }
    val screenLanguage = votes.maxByOrNull { it.value }?.key

    return detections.mapIndexed { i, d ->
      when {
        d.code == null && ScriptDetector.analyze(texts[i]).letters == 0 -> null
        d.confident -> d.code
        else -> resolveAmbiguous(texts[i], d.code, screenLanguage, fallback)
      }
    }
  }

  private fun resolveAmbiguous(text: String, guess: String?, screen: String?, fallback: String): String? {
    val stats = ScriptDetector.analyze(text)
    val script = stats.dominant
    // Prefer a context language that is written in the same script as this block.
    fun compatible(code: String?): Boolean {
      if (code == null) return false
      val langScript = Languages.byCode(code)?.script ?: return false
      return langScript == script || (script == Script.HAN && langScript == Script.KANA)
    }
    return when {
      compatible(screen) -> screen
      compatible(fallback) -> fallback
      guess != null -> guess
      else -> fallback
    }
  }

  companion object {
    const val MIN_CONFIDENCE = 0.5f
    const val MIN_LETTERS = 4
    const val LONG_HAN_RUN = 8
  }
}
