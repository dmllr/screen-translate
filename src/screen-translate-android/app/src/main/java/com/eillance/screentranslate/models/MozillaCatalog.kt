package com.eillance.screentranslate.models

import org.json.JSONObject

/**
 * Reads the Firefox Translations model registry from Mozilla Remote Settings, the same source
 * Firefox itself uses. Records are per-file (model / lex / vocab...) with a `filter_expression`
 * that targets platforms and channels; we evaluate those as "Android, release channel".
 */
object MozillaCatalog {

  const val RECORDS_URL =
    "https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/translations-models/records"
  const val ATTACHMENTS_BASE = "https://firefox-settings-attachments.cdn.mozilla.net/"

  data class Record(
    val from: String,
    val to: String,
    val fileType: String,
    val name: String,
    val version: String,
    val filter: String,
    val sha256: String,
    val size: Long,
    val location: String,
  )

  fun parse(json: String): List<Record> {
    val data = JSONObject(json).getJSONArray("data")
    val out = ArrayList<Record>(data.length())
    for (i in 0 until data.length()) {
      val r = data.getJSONObject(i)
      val att = r.optJSONObject("attachment") ?: continue
      out += Record(
        from = r.getString("fromLang"),
        to = r.getString("toLang"),
        fileType = r.getString("fileType"),
        name = r.getString("name"),
        version = r.getString("version"),
        filter = r.optString("filter_expression", ""),
        sha256 = att.getString("hash"),
        size = att.getLong("size"),
        location = att.getString("location"),
      )
    }
    return out
  }

  /** Builds one [ModelPack] per translation direction, choosing the best applicable version. */
  fun buildPacks(records: List<Record>, attachmentsBase: String = ATTACHMENTS_BASE): List<ModelPack> {
    val applicable = records.filter { FilterExpression.matches(it.filter, os = "Android", channel = "release") }
    return applicable
      .groupBy { it.from to it.to }
      .mapNotNull { (pair, recs) -> bestComplete(pair.first, pair.second, recs, attachmentsBase) }
      .sortedWith(compareBy({ it.from }, { it.to }))
  }

  private fun bestComplete(from: String, to: String, recs: List<Record>, base: String): ModelPack? {
    val byVersion = recs.groupBy { it.version }
    val versions = byVersion.keys.sortedWith(VersionComparator).reversed()
    for (version in versions) {
      val files = byVersion.getValue(version).associateBy { it.fileType }
      val model = files["model"] ?: continue
      val lex = files["lex"] ?: continue
      val vocabs = when {
        files["vocab"] != null -> listOf(files.getValue("vocab"))
        files["srcvocab"] != null && files["trgvocab"] != null -> listOf(files.getValue("srcvocab"), files.getValue("trgvocab"))
        else -> continue
      }
      val chosen = listOf(model, lex) + vocabs
      return ModelPack(
        id = ModelPack.translationId(from, to),
        kind = PackKind.TRANSLATION,
        title = "$from → $to",
        description = "Mozilla translation model $version",
        version = version,
        files = chosen.map { RemoteFile(base + it.location, it.name, it.sha256, it.size) },
        from = from,
        to = to,
        fileRoles = chosen.associate { it.name to it.fileType },
      )
    }
    return null
  }

  /** "2.0" > "2.0a1" (pre-release) ; "2.10" > "2.9". */
  object VersionComparator : Comparator<String> {
    private val re = Regex("""^(\d+(?:\.\d+)*)(.*)$""")
    override fun compare(a: String, b: String): Int {
      val (na, sa) = split(a)
      val (nb, sb) = split(b)
      for (i in 0 until maxOf(na.size, nb.size)) {
        val c = (na.getOrElse(i) { 0 }).compareTo(nb.getOrElse(i) { 0 })
        if (c != 0) return c
      }
      return when {
        sa.isEmpty() && sb.isNotEmpty() -> 1
        sa.isNotEmpty() && sb.isEmpty() -> -1
        else -> sa.compareTo(sb)
      }
    }

    private fun split(v: String): Pair<List<Int>, String> {
      val m = re.matchEntire(v.trim()) ?: return emptyList<Int>() to v
      return m.groupValues[1].split('.').map { it.toInt() } to m.groupValues[2]
    }
  }

  /**
   * Minimal evaluator for the JEXL subset used in this collection, e.g.
   * `env.appinfo.OS == 'Android'` or `env.appinfo.OS != 'Android' || env.channel != 'release'`.
   * Anything it does not understand evaluates to false, so unknown targeting is skipped.
   */
  object FilterExpression {
    private val atom = Regex("""^\(?\s*env\.(appinfo\.OS|channel)\s*(==|!=)\s*['"]([^'"]*)['"]\s*\)?$""")

    fun matches(expression: String, os: String, channel: String): Boolean {
      val expr = expression.trim()
      if (expr.isEmpty()) return true
      return expr.split("||").any { clause ->
        clause.split("&&").all { evalAtom(it.trim(), os, channel) }
      }
    }

    private fun evalAtom(text: String, os: String, channel: String): Boolean {
      val m = atom.matchEntire(text) ?: return false
      val (key, op, value) = m.destructured
      val actual = if (key == "appinfo.OS") os else channel
      return if (op == "==") actual == value else actual != value
    }
  }
}
