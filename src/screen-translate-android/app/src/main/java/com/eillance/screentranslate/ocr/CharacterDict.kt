package com.eillance.screentranslate.ocr

/**
 * Extracts `PostProcess.character_dict` from a PaddleX `inference.yml`.
 *
 * The file is large (tens of thousands of entries) and we only need this one list, so instead of
 * pulling in a YAML library we parse just the block-sequence scalars it contains: plain,
 * 'single-quoted' (with '' escapes) and "double-quoted" (with backslash escapes).
 */
object CharacterDict {

  fun parse(yaml: String): List<String> {
    val lines = yaml.lineSequence().iterator()
    var keyIndent = -1
    while (lines.hasNext()) {
      val line = lines.next()
      val trimmed = line.trimStart()
      if (trimmed.startsWith("character_dict:")) {
        keyIndent = line.length - trimmed.length
        break
      }
    }
    require(keyIndent >= 0) { "character_dict not found" }

    val out = ArrayList<String>(20_000)
    while (lines.hasNext()) {
      val line = lines.next()
      if (line.isBlank()) continue
      val trimmed = line.trimStart()
      val indent = line.length - trimmed.length
      // A sequence may be indented at the key's level ("key:\n- a") or deeper.
      if (!trimmed.startsWith("-") || indent < keyIndent) break
      if (trimmed != "-" && !trimmed.startsWith("- ")) break
      out += scalar(if (trimmed == "-") "" else trimmed.substring(2))
    }
    return out
  }

  internal fun scalar(raw: String): String {
    // Plain scalars here never carry trailing comments; quoted ones are fully delimited.
    val s = raw.trimEnd('\r')
    if (s.length >= 2 && s.startsWith("'") && s.endsWith("'")) {
      return s.substring(1, s.length - 1).replace("''", "'")
    }
    if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
      return unescapeDouble(s.substring(1, s.length - 1))
    }
    // Only strip ASCII blanks: entries such as U+3000 (ideographic space) are real characters.
    return s.trim(' ', '\t')
  }

  private fun unescapeDouble(s: String): String {
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
      val c = s[i]
      if (c != '\\' || i + 1 >= s.length) {
        sb.append(c); i++; continue
      }
      when (val e = s[i + 1]) {
        'n' -> { sb.append('\n'); i += 2 }
        't' -> { sb.append('\t'); i += 2 }
        '\\' -> { sb.append('\\'); i += 2 }
        '"' -> { sb.append('"'); i += 2 }
        '/' -> { sb.append('/'); i += 2 }
        '0' -> { sb.append('\u0000'); i += 2 }
        ' ' -> { sb.append(' '); i += 2 }
        'x', 'u', 'U' -> {
          val len = when (e) { 'x' -> 2; 'u' -> 4; else -> 8 }
          val hex = s.substring(i + 2, minOf(s.length, i + 2 + len))
          sb.appendCodePoint(hex.toInt(16))
          i += 2 + len
        }
        else -> { sb.append(e); i += 2 }
      }
    }
    return sb.toString()
  }
}
