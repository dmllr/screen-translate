package com.eillance.screentranslate.ocr

/**
 * Turns the DB (Differentiable Binarization) probability map of a PaddleOCR detector into text boxes.
 *
 * Simplified for screens: text there is almost always axis-aligned, so instead of minimum-area
 * rotated rectangles + polygon clipping we take connected components, their bounding boxes, and
 * "unclip" them by the same distance formula PaddleOCR uses (area * ratio / perimeter).
 */
class DbPostProcess(
  private val threshold: Float = 0.3f,
  private val boxThreshold: Float = 0.6f,
  private val unclipRatio: Float = 1.5f,
  private val minSize: Int = 3,
  private val maxCandidates: Int = 1000,
) {

  /** @return boxes in probability-map coordinates. */
  fun extract(prob: FloatArray, width: Int, height: Int): List<Box> {
    val n = width * height
    val mask = BooleanArray(n) { prob[it] > threshold }
    val visited = BooleanArray(n)
    val queue = IntArray(n)
    val boxes = ArrayList<Box>()

    for (start in 0 until n) {
      if (!mask[start] || visited[start]) continue
      // BFS flood fill (4-connectivity).
      var head = 0
      var tail = 0
      queue[tail++] = start
      visited[start] = true
      var minX = width; var minY = height; var maxX = -1; var maxY = -1
      var pixels = 0
      while (head < tail) {
        val p = queue[head++]
        val x = p % width
        val y = p / width
        pixels++
        if (x < minX) minX = x
        if (x > maxX) maxX = x
        if (y < minY) minY = y
        if (y > maxY) maxY = y
        if (x > 0) { val q = p - 1; if (mask[q] && !visited[q]) { visited[q] = true; queue[tail++] = q } }
        if (x < width - 1) { val q = p + 1; if (mask[q] && !visited[q]) { visited[q] = true; queue[tail++] = q } }
        if (y > 0) { val q = p - width; if (mask[q] && !visited[q]) { visited[q] = true; queue[tail++] = q } }
        if (y < height - 1) { val q = p + width; if (mask[q] && !visited[q]) { visited[q] = true; queue[tail++] = q } }
      }

      val w = maxX - minX + 1
      val h = maxY - minY + 1
      if (minOf(w, h) < minSize || pixels < minSize * minSize) continue

      if (meanInRect(prob, width, minX, minY, maxX, maxY) < boxThreshold) continue

      // Unclip: expand by d = A * r / L, as pyclipper offsetting does for a rectangle.
      val d = (w.toFloat() * h * unclipRatio) / (2f * (w + h))
      val box = Box(
        (minX - d).toInt().coerceAtLeast(0),
        (minY - d).toInt().coerceAtLeast(0),
        (maxX + 1 + d).toInt().coerceAtMost(width),
        (maxY + 1 + d).toInt().coerceAtMost(height),
      )
      if (minOf(box.width, box.height) < minSize + 2) continue
      boxes += box
      if (boxes.size >= maxCandidates) break
    }
    return boxes
  }

  private fun meanInRect(prob: FloatArray, width: Int, x0: Int, y0: Int, x1: Int, y1: Int): Float {
    var sum = 0.0
    for (y in y0..y1) {
      val row = y * width
      for (x in x0..x1) sum += prob[row + x]
    }
    return (sum / ((x1 - x0 + 1) * (y1 - y0 + 1))).toFloat()
  }
}
