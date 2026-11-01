package com.eillance.screentranslate.langid

import com.eillance.screentranslate.lang.Script

/** Per-script letter counts of a piece of text. Digits, punctuation and symbols are ignored. */
class ScriptStats(private val counts: IntArray) {
  val letters: Int = counts.sum()

  operator fun get(script: Script): Int = counts[script.ordinal]

  fun fraction(script: Script): Float = if (letters == 0) 0f else this[script].toFloat() / letters

  /** The script with the most letters, or null if the text has no letters at all. */
  val dominant: Script?
    get() {
      if (letters == 0) return null
      var best = 0
      for (i in counts.indices) if (counts[i] > counts[best]) best = i
      return Script.entries[best]
    }
}

object ScriptDetector {

  fun scriptOf(codePoint: Int): Script? = when (codePoint) {
    in 0x0041..0x005A, in 0x0061..0x007A, in 0x00C0..0x024F, in 0x1E00..0x1EFF,
    in 0xFF21..0xFF3A, in 0xFF41..0xFF5A -> if (codePoint == 0x00D7 || codePoint == 0x00F7) null else Script.LATIN
    in 0x3040..0x309F, in 0x30A0..0x30FF, in 0x31F0..0x31FF, in 0xFF66..0xFF9D -> Script.KANA
    in 0x4E00..0x9FFF, in 0x3400..0x4DBF, in 0xF900..0xFAFF, in 0x20000..0x2FA1F -> Script.HAN
    in 0xAC00..0xD7AF, in 0x1100..0x11FF, in 0x3130..0x318F -> Script.HANGUL
    in 0x0400..0x04FF, in 0x0500..0x052F -> Script.CYRILLIC
    in 0x0600..0x06FF, in 0x0750..0x077F, in 0xFB50..0xFDFF, in 0xFE70..0xFEFF -> Script.ARABIC
    in 0x0590..0x05FF -> Script.HEBREW
    in 0x0370..0x03FF, in 0x1F00..0x1FFF -> Script.GREEK
    in 0x0E00..0x0E7F -> Script.THAI
    in 0x0900..0x097F -> Script.DEVANAGARI
    in 0x0980..0x09FF -> Script.BENGALI
    in 0x0A80..0x0AFF -> Script.GUJARATI
    in 0x0C80..0x0CFF -> Script.KANNADA
    in 0x0D00..0x0D7F -> Script.MALAYALAM
    in 0x0B80..0x0BFF -> Script.TAMIL
    in 0x0C00..0x0C7F -> Script.TELUGU
    else -> null
  }

  fun analyze(text: String): ScriptStats {
    val counts = IntArray(Script.entries.size)
    var i = 0
    while (i < text.length) {
      val cp = text.codePointAt(i)
      scriptOf(cp)?.let { counts[it.ordinal]++ }
      i += Character.charCount(cp)
    }
    return ScriptStats(counts)
  }

  /**
   * Heuristic Simplified vs Traditional Chinese check: counts characters that exist in only one
   * of the two forms. Not exhaustive, but these are among the most frequent characters.
   */
  fun looksTraditional(text: String): Boolean {
    var trad = 0
    var simp = 0
    for (ch in text) {
      if (ch in TRADITIONAL_ONLY) trad++
      if (ch in SIMPLIFIED_ONLY) simp++
    }
    return trad > simp
  }

  private const val TRADITIONAL_ONLY =
    "這個們來時會說對國過後學為發當點經還動麼現實與開應關問種見長頭從東車給機電話體裡號" +
      "樣當間進題無認線產萬條紙級張辦極龍華變樂買賣讀寫聽專業戰與歡門語錢網際傳書區夠親愛"
  private const val SIMPLIFIED_ONLY =
    "这个们来时会说对国过后学为发当点经还动么现实与开应关问种见长头从东车给机电话体里号" +
      "样当间进题无认线产万条纸级张办极龙华变乐买卖读写听专业战与欢门语钱网际传书区够亲爱"
}
