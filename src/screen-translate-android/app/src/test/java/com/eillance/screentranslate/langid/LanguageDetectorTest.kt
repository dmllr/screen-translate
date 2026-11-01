package com.eillance.screentranslate.langid

import com.eillance.screentranslate.capture.ChangeDetector
import com.eillance.screentranslate.models.InstalledTranslation
import com.eillance.screentranslate.ocr.Box
import com.eillance.screentranslate.ocr.Frame
import com.eillance.screentranslate.translate.MissingModelException
import com.eillance.screentranslate.translate.RoutePlanner
import com.eillance.screentranslate.translate.TranslationRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class LanguageDetectorTest {

  /** Stands in for fastText: returns a fixed answer per text. */
  private fun classifier(vararg answers: Pair<String, LangGuess>) = LanguageClassifier { text, _ ->
    listOfNotNull(answers.toMap()[text])
  }

  @Test fun `kana means japanese without asking the classifier`() {
    val d = LanguageDetector(LanguageClassifier { _, _ -> error("should not be called") })
    assertEquals(Detection("ja", true), d.detect("設定を変更しました"))
  }

  @Test fun `unique scripts are decided directly`() {
    val d = LanguageDetector(null)
    assertEquals("ko", d.detect("안녕하세요").code)
    assertEquals("th", d.detect("สวัสดีครับ").code)
    assertEquals("el", d.detect("Καλημέρα").code)
  }

  @Test fun `latin text uses the classifier`() {
    val d = LanguageDetector(classifier("Guten Morgen zusammen" to LangGuess("de", 0.97f)))
    assertEquals(Detection("de", true), d.detect("Guten Morgen zusammen"))
  }

  @Test fun `kanji-only labels follow the japanese screen`() {
    val d = LanguageDetector(classifier("設定" to LangGuess("zh", 0.8f)))
    val langs = d.detectAll(listOf("今日はいい天気ですね", "設定", "12:30"), fallback = "en")
    assertEquals(listOf("ja", "ja", null), langs)
  }

  @Test fun `kanji-only with japanese fallback and no context`() {
    val d = LanguageDetector(classifier("設定" to LangGuess("zh", 0.8f)))
    assertEquals(listOf("ja"), d.detectAll(listOf("設定"), fallback = "ja"))
  }

  @Test fun `chinese screen stays chinese`() {
    val d = LanguageDetector(classifier("这个应用程序很好用" to LangGuess("zh", 0.99f)))
    assertEquals(listOf("zh-Hans"), d.detectAll(listOf("这个应用程序很好用"), fallback = "ja"))
  }

  @Test fun `short unconfident latin falls back to screen language`() {
    val d = LanguageDetector(
      classifier(
        "Das ist ein ganz normaler Satz" to LangGuess("de", 0.95f),
        "OK" to LangGuess("en", 0.3f),
      ),
    )
    assertEquals(listOf("de", "de"), d.detectAll(listOf("Das ist ein ganz normaler Satz", "OK"), fallback = "ja"))
  }
}

class RoutePlannerTest {
  private fun model(f: String, t: String) = InstalledTranslation(f, t, File("m"), File("l"), listOf(File("v"), File("v")))
  private val installed = setOf("ja" to "en", "en" to "de")
  private val lookup: (String, String) -> InstalledTranslation? = { f, t -> if ((f to t) in installed) model(f, t) else null }

  @Test fun `direct when available`() {
    assertTrue(RoutePlanner.plan("ja", "en", lookup) is TranslationRoute.Direct)
  }

  @Test fun `pivots through english`() {
    val r = RoutePlanner.plan("ja", "de", lookup) as TranslationRoute.Pivot
    assertEquals("ja" to "en", r.first.from to r.first.to)
    assertEquals("en" to "de", r.second.from to r.second.to)
  }

  @Test fun `reports exactly the missing legs`() {
    try {
      RoutePlanner.plan("ko", "de", lookup)
      fail()
    } catch (e: MissingModelException) {
      assertEquals(listOf("ko" to "en"), e.missing)
    }
  }
}

class ChangeDetectorTest {
  private fun frame(w: Int, h: Int, color: (Int, Int) -> Int) = Frame(IntArray(w * h) { color(it % w, it / w) }, w, h)

  @Test fun `ignores changes inside masked areas`() {
    val cd = ChangeDetector(cellSize = 10)
    val base = frame(100, 100) { _, _ -> 0xFFFFFFFF.toInt() }
    // E.g. the bubble moved or animated inside its masked area: not a content change.
    val withOverlay = frame(100, 100) { x, y -> if (x in 20..59 && y in 20..59) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    val mask = listOf(Box(20, 20, 60, 60))
    assertFalse(cd.changed(cd.signature(base, mask), cd.signature(withOverlay, mask)))
  }

  @Test fun `detects real content changes`() {
    val cd = ChangeDetector(cellSize = 10)
    val a = frame(100, 100) { _, _ -> 0xFFFFFFFF.toInt() }
    val b = frame(100, 100) { _, y -> if (y > 70) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    assertTrue(cd.changed(cd.signature(a, emptyList()), cd.signature(b, emptyList())))
  }
}
