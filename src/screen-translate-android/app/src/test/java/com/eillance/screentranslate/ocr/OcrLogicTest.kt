package com.eillance.screentranslate.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Kept as escapes: editors strip trailing U+3000 and rewrite \u sequences in raw strings.
private const val IDEOGRAPHIC_SPACE = "　"
private const val BACKSLASH = "\\"

class CharacterDictTest {
  @Test fun `parses plain, quoted and special entries`() {
    val yaml = """
      Global:
        model_name: X
      PostProcess:
        name: CTCLabelDecode
        character_dict:
        - $IDEOGRAPHIC_SPACE
        - '!'
        - ''''
        - "${BACKSLASH}u00e9"
        - a
        - 日本
      Other: 1
    """.trimIndent()
    assertEquals(listOf(IDEOGRAPHIC_SPACE, "!", "'", "é", "a", "日本"), CharacterDict.parse(yaml))
  }

  @Test fun `handles sequences at the key's indentation until end of file`() {
    val yaml = "PostProcess:\n  character_dict:\n  - x\n  - y\n"
    assertEquals(listOf("x", "y"), CharacterDict.parse(yaml))
  }
}

class CtcDecoderTest {
  // dictionary a,b ; classes: blank,a,b,space
  private val dict = listOf("a", "b")

  private fun probs(vararg best: Int, classes: Int = 4): FloatArray {
    val out = FloatArray(best.size * classes) { 0.01f }
    best.forEachIndexed { t, k -> out[t * classes + k] = 0.9f }
    return out
  }

  @Test fun `collapses repeats and removes blanks`() {
    val d = CtcDecoder(dict, numClasses = 4)
    // a a blank a b space b
    val r = d.decode(probs(1, 1, 0, 1, 2, 3, 2), 0, 7)
    assertEquals("aab b", r.text)
    assertEquals(0.9f, r.confidence, 1e-5f)
  }

  @Test fun `works without the space class`() {
    val d = CtcDecoder(dict, numClasses = 3)
    assertEquals("ab", d.decode(probs(1, 2, classes = 3), 0, 2).text)
  }

  @Test fun `reverses arabic visual order but keeps latin runs`() {
    assertEquals("ابت 123", CtcDecoder.reverseVisualOrder("123 تبا"))
    assertEquals("ابت hello world", CtcDecoder.reverseVisualOrder("hello world تبا"))
  }
}

class DbPostProcessTest {
  @Test fun `finds two separated text regions and unclips them`() {
    val w = 100
    val h = 40
    val prob = FloatArray(w * h)
    fun fill(x0: Int, y0: Int, x1: Int, y1: Int) {
      for (y in y0 until y1) for (x in x0 until x1) prob[y * w + x] = 0.95f
    }
    fill(10, 10, 40, 20)
    fill(60, 10, 90, 20)
    val boxes = DbPostProcess().extract(prob, w, h).sortedBy { it.left }
    assertEquals(2, boxes.size)
    val b = boxes[0]
    // Expanded beyond the raw 30x10 component on every side.
    assertTrue(b.left < 10 && b.top < 10 && b.right > 40 && b.bottom > 20)
  }

  @Test fun `drops low-score and tiny regions`() {
    val w = 50
    val prob = FloatArray(w * w)
    prob[5 * w + 5] = 0.99f                        // 1 pixel
    for (y in 20 until 30) for (x in 20 until 40) prob[y * w + x] = 0.35f  // above thresh, below box thresh
    assertTrue(DbPostProcess().extract(prob, w, w).isEmpty())
  }
}

class LayoutAnalyzerTest {
  private fun line(l: Int, t: Int, r: Int, b: Int, text: String, vertical: Boolean = false) =
    OcrLine(Box(l, t, r, b), text, 0.95f, vertical)

  @Test fun `merges paragraph lines and keeps separate blocks apart`() {
    val lines = listOf(
      line(10, 100, 300, 130, "Hello there,"),
      line(10, 135, 280, 165, "general Kenobi."),
      line(10, 400, 200, 430, "Settings"),
    )
    val blocks = LayoutAnalyzer.group(lines)
    assertEquals(2, blocks.size)
    assertEquals("Hello there, general Kenobi.", blocks[0].text)
    assertEquals("Settings", blocks[1].text)
  }

  @Test fun `joins japanese without spaces`() {
    val blocks = LayoutAnalyzer.group(
      listOf(line(10, 100, 300, 130, "今日はいい"), line(10, 134, 300, 164, "天気ですね。")),
    )
    assertEquals("今日はいい天気ですね。", blocks.single().text)
  }

  @Test fun `vertical columns read right to left`() {
    val blocks = LayoutAnalyzer.group(
      listOf(
        line(100, 10, 130, 200, "右の列", vertical = true),
        line(60, 10, 90, 180, "左の列", vertical = true),
      ),
    )
    assertEquals("右の列左の列", blocks.single().text)
  }

  @Test fun `undoes hyphenation`() {
    assertEquals("translation works", LayoutAnalyzer.joinLines(listOf("transla-", "tion works"), vertical = false))
  }
}

class ImageOpsTest {
  @Test fun `rotation maps the top of a column to the left of the line`() {
    // 1x3 column: red (top), green, blue (bottom).
    val argb = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt())
    val out = FloatArray(3 * 1 * 3)
    val written = ImageOps.writeRecognizerSample(argb, 1, 3, Box(0, 0, 1, 3), rotate = true, out, 0, height = 1, batchWidth = 3)
    assertEquals(3, written)
    // Channel order is BGR; R plane is index 2. Red should be at x=0, blue (B plane) at x=2.
    val plane = 3
    assertEquals(1f, out[2 * plane + 0], 1e-3f)
    assertEquals(1f, out[0 * plane + 2], 1e-3f)
  }
}
