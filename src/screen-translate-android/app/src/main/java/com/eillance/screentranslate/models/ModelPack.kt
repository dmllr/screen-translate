package com.eillance.screentranslate.models

import com.eillance.screentranslate.lang.Script

/** A single file to download. [sha256] is verified after download when present. */
data class RemoteFile(
  val url: String,
  val fileName: String,
  val sha256: String?,
  val size: Long,
)

enum class PackKind { TRANSLATION, OCR_DETECTOR, OCR_RECOGNIZER, LANGUAGE_ID }

/** A downloadable unit: one translation direction, one OCR model, or the language-ID model. */
data class ModelPack(
  val id: String,
  val kind: PackKind,
  val title: String,
  val description: String,
  val version: String,
  val files: List<RemoteFile>,
  /** OCR recognizers: scripts this recognizer reads. */
  val scripts: Set<Script> = emptySet(),
  /** Translation packs: direction. */
  val from: String? = null,
  val to: String? = null,
  /** Translation packs: role of each file, keyed by file name. */
  val fileRoles: Map<String, String> = emptyMap(),
  /** OCR recognizers: decoded text is in visual order and must be reversed (Arabic script). */
  val rightToLeft: Boolean = false,
) {
  val totalSize: Long get() = files.sumOf { it.size }

  companion object {
    fun translationId(from: String, to: String) = "translate-$from-$to"
  }
}
