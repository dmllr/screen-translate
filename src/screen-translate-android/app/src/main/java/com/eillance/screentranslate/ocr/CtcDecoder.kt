package com.eillance.screentranslate.ocr

/**
 * Greedy CTC decoding, matching PaddleOCR's `CTCLabelDecode`:
 * index 0 is the blank, indices 1..N map to the dictionary, and when the model has one more
 * class than that, the last class is a space (`use_space_char: true`).
 */
class CtcDecoder(dictionary: List<String>, numClasses: Int, private val rightToLeft: Boolean = false) {

  private val labels: Array<String> = run {
    val withSpace = numClasses == dictionary.size + 2
    require(withSpace || numClasses == dictionary.size + 1) {
      "Model has $numClasses classes but dictionary has ${dictionary.size} entries"
    }
    Array(numClasses) { i ->
      when {
        i == 0 -> ""
        i <= dictionary.size -> dictionary[i - 1]
        else -> " "
      }
    }
  }

  data class Result(val text: String, val confidence: Float)

  /**
   * @param probs softmax output for one sample laid out as [timeSteps][numClasses] starting at [offset].
   */
  fun decode(probs: FloatArray, offset: Int, timeSteps: Int): Result {
    val c = labels.size
    val sb = StringBuilder()
    var scoreSum = 0f
    var count = 0
    var prev = -1
    for (t in 0 until timeSteps) {
      val base = offset + t * c
      var best = 0
      var bestP = probs[base]
      for (k in 1 until c) {
        val p = probs[base + k]
        if (p > bestP) { bestP = p; best = k }
      }
      if (best != 0 && best != prev) {
        sb.append(labels[best])
        scoreSum += bestP
        count++
      }
      prev = best
    }
    val text = sb.toString().trim()
    val conf = if (count == 0) 0f else scoreSum / count
    return Result(if (rightToLeft) reverseVisualOrder(text) else text, conf)
  }

  companion object {
    private val LTR_RUN = Regex("[a-zA-Z0-9 :*./%+\\-]+")

    /**
     * Arabic-script recognizers emit characters in visual (left-to-right) order. Reverse the
     * RTL parts to logical order while keeping embedded Latin/number runs intact
     * (same approach as PaddleOCR's `pred_reverse`).
     */
    fun reverseVisualOrder(text: String): String {
      val parts = ArrayList<String>()
      var last = 0
      for (m in LTR_RUN.findAll(text)) {
        if (m.range.first > last) parts += text.substring(last, m.range.first).reversed()
        // Spaces at the edges of a run separate it from the RTL text; keep them as their own
        // parts so they end up on the correct side after reversal.
        val core = m.value.trim(' ')
        val lead = m.value.length - m.value.trimStart(' ').length
        val trail = m.value.length - m.value.trimEnd(' ').length
        if (lead > 0) parts += " ".repeat(lead)
        if (core.isNotEmpty()) parts += core
        if (trail > 0 && core.isNotEmpty()) parts += " ".repeat(trail)
        last = m.range.last + 1
      }
      if (last < text.length) parts += text.substring(last).reversed()
      return parts.reversed().joinToString("")
    }
  }
}
