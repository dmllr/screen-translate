package com.eillance.screentranslate.ocr

/** Integer axis-aligned rectangle, [right]/[bottom] exclusive. Plain class so it works in JVM tests. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
  val width: Int get() = right - left
  val height: Int get() = bottom - top
  val centerX: Float get() = (left + right) / 2f
  val centerY: Float get() = (top + bottom) / 2f
  val area: Long get() = width.toLong() * height

  fun union(o: Box) = Box(minOf(left, o.left), minOf(top, o.top), maxOf(right, o.right), maxOf(bottom, o.bottom))

  fun scaled(sx: Float, sy: Float) =
    Box((left * sx).toInt(), (top * sy).toInt(), (right * sx).toInt(), (bottom * sy).toInt())

  fun clampTo(w: Int, h: Int) = Box(left.coerceIn(0, w), top.coerceIn(0, h), right.coerceIn(0, w), bottom.coerceIn(0, h))

  fun intersects(o: Box) = left < o.right && o.left < right && top < o.bottom && o.top < bottom

  fun offset(dx: Int, dy: Int) = Box(left + dx, top + dy, right + dx, bottom + dy)
}

/** One detected and recognized text line. */
data class OcrLine(
  val box: Box,
  val text: String,
  val confidence: Float,
  /** Column of top-to-bottom text (e.g. vertical Japanese). */
  val vertical: Boolean,
)

/** Lines grouped into a paragraph-like unit; the unit of translation. */
data class TextBlock(
  val box: Box,
  val lines: List<OcrLine>,
  val text: String,
  val vertical: Boolean,
)
