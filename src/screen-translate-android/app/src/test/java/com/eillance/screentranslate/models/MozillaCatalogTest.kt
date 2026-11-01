package com.eillance.screentranslate.models

import com.eillance.screentranslate.models.MozillaCatalog.FilterExpression
import com.eillance.screentranslate.models.MozillaCatalog.VersionComparator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MozillaCatalogTest {

  @Test fun `filter expressions evaluate for Android release`() {
    fun m(e: String) = FilterExpression.matches(e, os = "Android", channel = "release")
    assertTrue(m(""))
    assertTrue(m("env.appinfo.OS == 'Android' "))
    assertFalse(m("env.appinfo.OS != 'Android' || env.channel != 'release'"))
    assertFalse(m("env.channel == 'default' || env.channel == 'nightly' || env.channel == 'beta'"))
    assertTrue(m("env.appinfo.OS == 'Android' && env.channel == 'release'"))
    assertFalse(m("someUnknownThing() == 1"))
  }

  @Test fun `versions compare numerically with pre-releases lower`() {
    assertTrue(VersionComparator.compare("2.0", "2.0a1") > 0)
    assertTrue(VersionComparator.compare("2.10", "2.9") > 0)
    assertTrue(VersionComparator.compare("2.3", "2.2") > 0)
    assertEquals(0, VersionComparator.compare("1.0", "1.0.0"))
  }

  private fun record(from: String, to: String, type: String, version: String, filter: String = "", name: String = "$type.$from$to") =
    """{"fromLang":"$from","toLang":"$to","fileType":"$type","name":"$name","version":"$version",
       "filter_expression":"$filter","attachment":{"hash":"h-$name-$version","size":10,"location":"loc/$name-$version"}}"""

  @Test fun `picks the newest complete version that applies to Android`() {
    val json = """{"data":[
      ${record("ja", "en", "model", "2.1", "env.appinfo.OS == 'Android'", "model.jaen.intgemm.alphas.bin")},
      ${record("ja", "en", "lex", "2.1", "env.appinfo.OS == 'Android'")},
      ${record("ja", "en", "vocab", "2.1", "env.appinfo.OS == 'Android'")},
      ${record("ja", "en", "model", "2.2", "env.appinfo.OS != 'Android' || env.channel != 'release'")},
      ${record("ja", "en", "lex", "2.2", "env.appinfo.OS != 'Android' || env.channel != 'release'")},
      ${record("ja", "en", "vocab", "2.2", "env.appinfo.OS != 'Android' || env.channel != 'release'")},
      ${record("en", "ja", "model", "2.3")},
      ${record("en", "ja", "lex", "2.3")},
      ${record("en", "ja", "srcvocab", "2.3")},
      ${record("en", "ja", "trgvocab", "2.3")},
      ${record("en", "ko", "model", "9.0")},
      ${record("en", "ko", "lex", "9.0")}
    ]}"""
    val packs = MozillaCatalog.buildPacks(MozillaCatalog.parse(json), "https://cdn/")

    val jaEn = packs.single { it.from == "ja" && it.to == "en" }
    assertEquals("2.1", jaEn.version)
    assertEquals(3, jaEn.files.size)
    assertTrue(jaEn.files.all { it.url.startsWith("https://cdn/loc/") })
    assertEquals("model", jaEn.fileRoles["model.jaen.intgemm.alphas.bin"])

    val enJa = packs.single { it.from == "en" && it.to == "ja" }
    assertEquals(setOf("model", "lex", "srcvocab", "trgvocab"), enJa.fileRoles.values.toSet())

    // en->ko has no vocab: incomplete, so not offered.
    assertNull(packs.firstOrNull { it.to == "ko" })
  }
}
