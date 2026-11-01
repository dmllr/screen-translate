package com.eillance.screentranslate.lang

/** Writing systems we can tell apart from Unicode ranges alone. */
enum class Script {
  LATIN, HAN, KANA, HANGUL, CYRILLIC, ARABIC, HEBREW, GREEK, THAI,
  DEVANAGARI, BENGALI, GUJARATI, KANNADA, MALAYALAM, TAMIL, TELUGU,
}

/**
 * A language the app knows about. [code] follows Mozilla's Firefox Translations codes
 * (ISO 639-1, plus `zh-Hans` / `zh-Hant`), which is what the translation models are keyed by.
 */
data class Language(val code: String, val name: String, val script: Script) {
  override fun toString(): String = name
}

object Languages {
  const val ENGLISH = "en"

  val all: List<Language> = listOf(
    Language("af", "Afrikaans", Script.LATIN),
    Language("sq", "Albanian", Script.LATIN),
    Language("ar", "Arabic", Script.ARABIC),
    Language("az", "Azerbaijani", Script.LATIN),
    Language("eu", "Basque", Script.LATIN),
    Language("be", "Belarusian", Script.CYRILLIC),
    Language("bn", "Bengali", Script.BENGALI),
    Language("bs", "Bosnian", Script.LATIN),
    Language("bg", "Bulgarian", Script.CYRILLIC),
    Language("ca", "Catalan", Script.LATIN),
    Language("zh-Hans", "Chinese (Simplified)", Script.HAN),
    Language("zh-Hant", "Chinese (Traditional)", Script.HAN),
    Language("hr", "Croatian", Script.LATIN),
    Language("cs", "Czech", Script.LATIN),
    Language("da", "Danish", Script.LATIN),
    Language("nl", "Dutch", Script.LATIN),
    Language("en", "English", Script.LATIN),
    Language("et", "Estonian", Script.LATIN),
    Language("tl", "Filipino", Script.LATIN),
    Language("fi", "Finnish", Script.LATIN),
    Language("fr", "French", Script.LATIN),
    Language("gl", "Galician", Script.LATIN),
    Language("de", "German", Script.LATIN),
    Language("el", "Greek", Script.GREEK),
    Language("gu", "Gujarati", Script.GUJARATI),
    Language("he", "Hebrew", Script.HEBREW),
    Language("hi", "Hindi", Script.DEVANAGARI),
    Language("hu", "Hungarian", Script.LATIN),
    Language("is", "Icelandic", Script.LATIN),
    Language("id", "Indonesian", Script.LATIN),
    Language("it", "Italian", Script.LATIN),
    Language("ja", "Japanese", Script.KANA),
    Language("kn", "Kannada", Script.KANNADA),
    Language("ko", "Korean", Script.HANGUL),
    Language("lv", "Latvian", Script.LATIN),
    Language("lt", "Lithuanian", Script.LATIN),
    Language("ms", "Malay", Script.LATIN),
    Language("ml", "Malayalam", Script.MALAYALAM),
    Language("mt", "Maltese", Script.LATIN),
    Language("mr", "Marathi", Script.DEVANAGARI),
    Language("nb", "Norwegian Bokmål", Script.LATIN),
    Language("nn", "Norwegian Nynorsk", Script.LATIN),
    Language("fa", "Persian", Script.ARABIC),
    Language("pl", "Polish", Script.LATIN),
    Language("pt", "Portuguese", Script.LATIN),
    Language("ro", "Romanian", Script.LATIN),
    Language("ru", "Russian", Script.CYRILLIC),
    Language("sr", "Serbian", Script.CYRILLIC),
    Language("sk", "Slovak", Script.LATIN),
    Language("sl", "Slovenian", Script.LATIN),
    Language("es", "Spanish", Script.LATIN),
    Language("sv", "Swedish", Script.LATIN),
    Language("ta", "Tamil", Script.TAMIL),
    Language("te", "Telugu", Script.TELUGU),
    Language("th", "Thai", Script.THAI),
    Language("tr", "Turkish", Script.LATIN),
    Language("uk", "Ukrainian", Script.CYRILLIC),
    Language("ur", "Urdu", Script.ARABIC),
    Language("ug", "Uyghur", Script.ARABIC),
    Language("vi", "Vietnamese", Script.LATIN),
  )

  private val byCode = all.associateBy { it.code }

  fun byCode(code: String): Language? = byCode[code]

  fun name(code: String): String = byCode[code]?.name ?: code

  /**
   * Maps a fastText lid.176 label to our code. Returns null for languages we cannot translate.
   * Chinese is returned as plain "zh"; the caller decides Simplified vs Traditional from the glyphs.
   */
  fun fromFastText(label: String): String? = when (label) {
    "zh" -> "zh"
    "no" -> "nb"
    "iw" -> "he"
    "fil" -> "tl"
    "sh" -> "sr"
    else -> label.takeIf { it in byCode }
  }
}
