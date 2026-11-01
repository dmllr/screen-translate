package com.eillance.screentranslate.ocr

import com.eillance.screentranslate.lang.Script
import com.eillance.screentranslate.langid.ScriptDetector
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Groups OCR lines into blocks (paragraphs, speech bubbles, list items) so that each block is
 * translated as one unit with its full sentence context.
 *
 * Horizontal lines merge with the line below when they are close, of similar height and
 * overlap horizontally. Vertical columns (vertical Japanese, manga) merge with the column to the
 * left, and read right-to-left.
 */
object LayoutAnalyzer {

  fun group(lines: List<OcrLine>): List<TextBlock> {
    val usable = lines.filter { it.text.isNotBlank() }
    if (usable.isEmpty()) return emptyList()

    val parent = IntArray(usable.size) { it }
    fun find(i: Int): Int {
      var x = i
      while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }
      return x
    }
    fun union(a: Int, b: Int) { parent[find(a)] = find(b) }

    for (i in usable.indices) for (j in i + 1 until usable.size) {
      val a = usable[i]
      val b = usable[j]
      if (a.vertical != b.vertical) continue
      val joined = if (a.vertical) sameColumnBlock(a.box, b.box) else sameParagraph(a.box, b.box)
      if (joined) union(i, j)
    }

    return usable.indices.groupBy(::find).values.map { idx ->
      val members = idx.map { usable[it] }
      val vertical = members.first().vertical
      val ordered = if (vertical) {
        members.sortedWith(compareByDescending<OcrLine> { it.box.centerX }.thenBy { it.box.top })
      } else {
        sortReadingOrder(members)
      }
      TextBlock(
        box = ordered.map { it.box }.reduce(Box::union),
        lines = ordered,
        text = joinLines(ordered.map { it.text }, vertical),
        vertical = vertical,
      )
    }.sortedWith(compareBy<TextBlock> { it.box.top }.thenBy { it.box.left })
  }

  private fun sameParagraph(a: Box, b: Box): Boolean {
    val ha = a.height.toFloat()
    val hb = b.height.toFloat()
    if (max(ha, hb) / max(1f, min(ha, hb)) > 1.6f) return false
    val h = (ha + hb) / 2f

    // Same row, small gap: detector split one line at a wide space.
    val verticalOverlap = min(a.bottom, b.bottom) - max(a.top, b.top)
    if (verticalOverlap > 0.6f * min(ha, hb)) {
      val gap = max(a.left, b.left) - min(a.right, b.right)
      return gap < 0.9f * h
    }

    // Consecutive rows: small vertical gap + horizontal overlap or shared left edge.
    val (upper, lower) = if (a.top <= b.top) a to b else b to a
    val vGap = lower.top - upper.bottom
    if (vGap > 0.75f * h || vGap < -0.5f * h) return false
    val hOverlap = min(a.right, b.right) - max(a.left, b.left)
    val leftAligned = abs(a.left - b.left) < 1.2f * h
    return hOverlap > 0.3f * min(a.width, b.width) || leftAligned && hOverlap > 0
  }

  private fun sameColumnBlock(a: Box, b: Box): Boolean {
    val wa = a.width.toFloat()
    val wb = b.width.toFloat()
    if (max(wa, wb) / max(1f, min(wa, wb)) > 1.6f) return false
    val w = (wa + wb) / 2f
    val hGap = max(a.left, b.left) - min(a.right, b.right)
    if (hGap > 0.8f * w) return false
    val vOverlap = min(a.bottom, b.bottom) - max(a.top, b.top)
    return vOverlap > 0.3f * min(a.height, b.height)
  }

  /** Rows top-to-bottom; within a row (vertically overlapping), left-to-right. */
  private fun sortReadingOrder(lines: List<OcrLine>): List<OcrLine> {
    val sorted = lines.sortedBy { it.box.top }
    val rows = ArrayList<MutableList<OcrLine>>()
    for (line in sorted) {
      val row = rows.lastOrNull()
      val ref = row?.first()?.box
      if (ref != null && line.box.centerY < ref.bottom && line.box.centerY > ref.top) row.add(line) else rows += mutableListOf(line)
    }
    return rows.flatMap { r -> r.sortedBy { it.box.left } }
  }

  /** CJK/Thai text has no spaces between words; everything else is joined with spaces. */
  fun joinLines(texts: List<String>, vertical: Boolean): String {
    val sb = StringBuilder()
    for (t in texts) {
      val text = t.trim()
      if (text.isEmpty()) continue
      if (sb.isEmpty()) { sb.append(text); continue }
      val prev = sb[sb.length - 1]
      val next = text[0]
      when {
        vertical || (isUnspaced(prev) && isUnspaced(next)) -> sb.append(text)
        prev == '-' && next.isLowerCase() -> { sb.setLength(sb.length - 1); sb.append(text) }  // hyphenation
        else -> sb.append(' ').append(text)
      }
    }
    return sb.toString()
  }

  private fun isUnspaced(c: Char): Boolean {
    val s = ScriptDetector.scriptOf(c.code)
    return s == Script.HAN || s == Script.KANA || s == Script.THAI ||
      c.code in 0x3000..0x303F || c.code in 0xFF01..0xFF20 || c.code in 0xFF3B..0xFF40  // CJK punctuation / fullwidth forms
  }
}
