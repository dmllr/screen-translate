package com.eillance.screentranslate.capture

import com.eillance.screentranslate.ocr.Box
import com.eillance.screentranslate.ocr.Frame
import kotlin.math.abs

/**
 * Cheap "did the screen content change?" check for continuous mode.
 *
 * The frame is reduced to a grid of mean-luminance cells. Frames are compared against a reference
 * captured with our overlay showing, so our own translations match themselves. Masked cells
 * (bubble, status bar) are ignored entirely.
 */
class ChangeDetector(private val cellSize: Int = 24, private val pixelDelta: Int = 10, private val minChangedCells: Int = 3) {

  class Signature(val cols: Int, val rows: Int, val cells: IntArray, val masked: BooleanArray)

  fun signature(frame: Frame, mask: List<Box>): Signature {
    val cols = (frame.width + cellSize - 1) / cellSize
    val rows = (frame.height + cellSize - 1) / cellSize
    val sums = LongArray(cols * rows)
    val counts = IntArray(cols * rows)
    // Sample every 3rd pixel in each direction: plenty for change detection, 9x cheaper.
    val step = 3
    var y = 0
    while (y < frame.height) {
      val row = y * frame.width
      val cy = (y / cellSize) * cols
      var x = 0
      while (x < frame.width) {
        val p = frame.argb[row + x]
        val lum = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
        val c = cy + x / cellSize
        sums[c] += lum.toLong()
        counts[c]++
        x += step
      }
      y += step
    }
    val cells = IntArray(cols * rows) { if (counts[it] == 0) 0 else (sums[it] / counts[it]).toInt() }
    val masked = BooleanArray(cols * rows)
    for (b in mask) {
      val c0 = (b.left / cellSize).coerceIn(0, cols - 1)
      val c1 = ((b.right - 1) / cellSize).coerceIn(0, cols - 1)
      val r0 = (b.top / cellSize).coerceIn(0, rows - 1)
      val r1 = ((b.bottom - 1) / cellSize).coerceIn(0, rows - 1)
      for (r in r0..r1) for (c in c0..c1) masked[r * cols + c] = true
    }
    return Signature(cols, rows, cells, masked)
  }

  /** True if [a] and [b] differ in enough cells that neither of them masks. */
  fun changed(a: Signature, b: Signature): Boolean {
    if (a.cols != b.cols || a.rows != b.rows) return true
    var changed = 0
    for (i in a.cells.indices) {
      if (a.masked[i] || b.masked[i]) continue
      if (abs(a.cells[i] - b.cells[i]) > pixelDelta && ++changed >= minChangedCells) return true
    }
    return false
  }
}
