// Open-source license notices shown in the app (Settings → Open-source licenses).
//
// The table below is the single source of truth. Texts are copied from the pinned sources that
// CMake's import_remote() clones into <repo>/_imported (and from ONNX Runtime's GitHub release for
// the version in libs.versions.toml) into src/main/assets/licenses/, which is committed.
//
//   ./gradlew :app:updateLicenses   regenerate after bumping a dependency (needs _imported + network)
//   ./gradlew :app:verifyLicenses   part of `check`: fails if committed texts drift from the sources

import groovy.json.JsonOutput
import java.net.URI

enum class Section(val title: String) {
  NATIVE("Translation engine (native code)"),
  APP("App libraries"),
  MODELS("Downloadable models (not bundled; fetched on demand from their publishers)"),
}

/** Where a license text comes from. */
sealed interface TextSource {
  /** A file under <repo>/_imported, optionally only a line range (1-based, inclusive). */
  data class Imported(val path: String, val lines: IntRange? = null) : TextSource
  data class Remote(val url: String) : TextSource
  data class Inline(val text: String) : TextSource
  /** A file in this repository, relative to the repo root. */
  data class Repo(val path: String) : TextSource
}

data class Component(
  val name: String,
  val version: String,
  val license: String,
  val url: String,
  val file: String,             // text file in assets/licenses/
  val source: TextSource?,      // null: the file is produced by another component's entry
  val section: Section,
  val note: String? = null,
)

val repoRoot: File = rootDir.resolve("../..").canonicalFile
val importedDir: File = repoRoot.resolve("_imported")
val assetsDir: File = projectDir.resolve("src/main/assets/licenses")

val ortVersion: String = Regex("""onnxruntime\s*=\s*"([^"]+)"""")
  .find(rootDir.resolve("gradle/libs.versions.toml").readText())!!.groupValues[1]

// Must match TAG in src/cmake/libbergamot.cmake (verifyLicenses checks the folder exists).
val translationsTag = "8eada038fd45"
val translationsCommit = "8eada038fd45bbf6fa58a412b22f80c79887fabc"
val tr = "github-com/mozilla/translations/$translationsTag"
val m3 = "$tr/inference/marian-fork/src/3rd_party"
val spm3 = "$m3/sentencepiece/third_party"

val ccBySa = """
  fastText language identification model (lid.176.ftz)
  Author: Facebook AI Research (Meta), https://fasttext.cc/docs/en/language-identification.html
  Licensed under the Creative Commons Attribution-ShareAlike 3.0 Unported License (CC BY-SA 3.0):
  https://creativecommons.org/licenses/by-sa/3.0/legalcode

  The model is downloaded unmodified from Meta's server at
  https://dl.fbaipublicfiles.com/fasttext/supervised-models/lid.176.ftz
  and is not distributed with this app.
""".trimIndent() + "\n"

val components = listOf(
  // --- compiled into libscreentranslate.so ---------------------------------------------------
  Component("Bergamot translator (Mozilla)", translationsTag, "MPL-2.0",
    "https://github.com/mozilla/translations/tree/$translationsCommit/inference", "mpl-2.0.txt",
    TextSource.Imported("$tr/LICENSE"), Section.NATIVE,
    "Modified: translation_model.h/.cpp (thread-safe replica loading). Source: upstream at " +
      "https://github.com/mozilla/translations/tree/$translationsCommit/inference plus the patch listed below."),
  Component("Source changes to Bergamot, Marian and SentencePiece (patch)", translationsTag, "MPL-2.0 / MIT / Apache-2.0",
    "https://github.com/mozilla/translations/tree/$translationsCommit/inference", "translations.patch.txt",
    TextSource.Repo("src/cmake/patches/translations.patch"), Section.NATIVE,
    "Complete source of our modifications, applied on top of the upstream revision."),
  Component("Marian NMT (Mozilla fork)", translationsTag, "MIT",
    "https://github.com/mozilla/translations/tree/$translationsCommit/inference/marian-fork", "marian.txt",
    TextSource.Imported("$tr/inference/marian-fork/LICENSE.md"), Section.NATIVE),
  Component("intgemm", "bundled", "MIT", "https://github.com/kpu/intgemm", "intgemm.txt",
    TextSource.Imported("$m3/intgemm/LICENSE"), Section.NATIVE),
  Component("ruy", "bundled", "Apache-2.0", "https://github.com/google/ruy", "apache-2.0.txt",
    TextSource.Imported("$m3/ruy/LICENSE"), Section.NATIVE),
  Component("cpuinfo", "bundled", "BSD-2-Clause", "https://github.com/pytorch/cpuinfo", "cpuinfo.txt",
    TextSource.Imported("$m3/ruy/third_party/cpuinfo/LICENSE"), Section.NATIVE),
  Component("simd_utils", "bundled", "BSD-2-Clause", "https://github.com/JishinMaster/simd_utils", "simd_utils.txt",
    TextSource.Imported("$m3/simd_utils/LICENSE"), Section.NATIVE),
  Component("SentencePiece", "bundled", "Apache-2.0", "https://github.com/browsermt/sentencepiece", "apache-2.0.txt",
    null, Section.NATIVE,
    "Modified: src/trainer_interface.cc (constexpr → const for NDK 29); see src/cmake/patches/translations.patch."),
  Component("Abseil (subset bundled in SentencePiece)", "bundled", "Apache-2.0", "https://github.com/abseil/abseil-cpp",
    "apache-2.0.txt", null, Section.NATIVE),
  Component("Protocol Buffers lite (bundled in SentencePiece)", "bundled", "BSD-3-Clause",
    "https://github.com/protocolbuffers/protobuf", "protobuf.txt",
    TextSource.Imported("$spm3/protobuf-lite/LICENSE"), Section.NATIVE),
  Component("darts-clone", "bundled", "BSD-2-Clause", "https://github.com/s-yata/darts-clone", "darts-clone.txt",
    TextSource.Imported("$spm3/darts_clone/LICENSE"), Section.NATIVE),
  Component("esaxx", "bundled", "MIT", "https://github.com/hillbig/esaxx", "esaxx.txt",
    TextSource.Imported("$spm3/esaxx/LICENSE"), Section.NATIVE),
  Component("ssplit-cpp", "bundled", "Apache-2.0", "https://github.com/browsermt/ssplit-cpp", "ssplit-cpp.txt",
    TextSource.Imported("$tr/inference/3rd_party/ssplit-cpp/LICENSE.md"), Section.NATIVE,
    "Only the Apache-2.0 C++ code is used; the LGPL nonbreaking_prefixes files are not included."),
  Component("PCRE2", "10.39", "BSD-3-Clause", "https://github.com/PCRE2Project/pcre2", "pcre2.txt",
    TextSource.Imported("github-com/PCRE2Project/pcre2/pcre2-10.39/LICENCE"), Section.NATIVE),
  Component("faiss (subset bundled in Marian)", "bundled", "MIT", "https://github.com/facebookresearch/faiss",
    "faiss.txt", TextSource.Imported("$m3/faiss/LICENSE"), Section.NATIVE,
    "Modified: VectorTransform.h (include <x86intrin.h> on Android); see src/cmake/patches/translations.patch."),
  Component("yaml-cpp", "bundled", "MIT", "https://github.com/jbeder/yaml-cpp", "yaml-cpp.txt",
    TextSource.Imported("$m3/yaml-cpp/LICENSE"), Section.NATIVE),
  Component("spdlog", "bundled", "MIT", "https://github.com/gabime/spdlog", "spdlog.txt",
    TextSource.Imported("$m3/spdlog/LICENSE"), Section.NATIVE),
  Component("CLI11", "1.6", "BSD-3-Clause", "https://github.com/CLIUtils/CLI11", "cli11.txt",
    TextSource.Imported("$m3/CLI/LICENSE"), Section.NATIVE),
  Component("pathie-cpp", "bundled", "BSD-2-Clause", "https://github.com/Quintus/pathie-cpp", "pathie-cpp.txt",
    TextSource.Imported("$m3/pathie-cpp/LICENSE"), Section.NATIVE),
  Component("cnpy", "bundled", "MIT", "https://github.com/rogersce/cnpy", "cnpy.txt",
    TextSource.Imported("$m3/cnpy/LICENSE"), Section.NATIVE),
  Component("mio", "bundled", "MIT", "https://github.com/mandreyel/mio", "mio.txt",
    TextSource.Imported("$m3/mio/LICENSE"), Section.NATIVE),
  Component("phf", "bundled", "MIT", "https://github.com/wahern/phf", "phf.txt",
    TextSource.Imported("$m3/phf/LICENSE"), Section.NATIVE),
  Component("zstr", "bundled", "MIT", "https://github.com/mateidavid/zstr", "zstr.txt",
    TextSource.Imported("$m3/zstr/LICENSE"), Section.NATIVE),
  Component("zlib", "bundled", "Zlib", "https://zlib.net", "zlib.txt",
    TextSource.Imported("$m3/zlib/zlib.h", 1..29), Section.NATIVE),
  Component("fastText", "0.9.2", "MIT", "https://github.com/facebookresearch/fastText", "fasttext.txt",
    TextSource.Imported("github-com/facebookresearch/fastText/v0.9.2/LICENSE"), Section.NATIVE),

  // --- Java/Kotlin libraries ------------------------------------------------------------------
  Component("ONNX Runtime", ortVersion, "MIT", "https://github.com/microsoft/onnxruntime", "onnxruntime.txt",
    TextSource.Remote("https://raw.githubusercontent.com/microsoft/onnxruntime/v$ortVersion/LICENSE"), Section.APP),
  Component("ONNX Runtime third-party notices", ortVersion, "Various",
    "https://github.com/microsoft/onnxruntime/blob/v$ortVersion/ThirdPartyNotices.txt", "onnxruntime-notices.txt",
    TextSource.Remote("https://raw.githubusercontent.com/microsoft/onnxruntime/v$ortVersion/ThirdPartyNotices.txt"),
    Section.APP, "Components compiled into libonnxruntime.so (protobuf, abseil, XNNPACK, Eigen, …)."),
  Component("Eigen (bundled in ONNX Runtime)", "bundled", "MPL-2.0", "https://gitlab.com/libeigen/eigen",
    "mpl-2.0.txt", null, Section.APP, "Unmodified. Source code: https://gitlab.com/libeigen/eigen"),
  Component("AndroidX, Jetpack Compose, Material 3", "", "Apache-2.0", "https://developer.android.com/jetpack/androidx",
    "apache-2.0.txt", null, Section.APP),
  Component("Kotlin standard library, kotlinx.coroutines", "", "Apache-2.0", "https://kotlinlang.org",
    "apache-2.0.txt", null, Section.APP),

  // --- models (downloaded, not bundled) --------------------------------------------------------
  Component("Translation models (Mozilla, Firefox Translations project)", "", "MPL-2.0",
    "https://github.com/mozilla/translations", "mpl-2.0.txt", null, Section.MODELS,
    "Downloaded from Mozilla's model registry."),
  Component("PaddleOCR PP-OCRv6 / PP-OCRv5 models (PaddlePaddle)", "", "Apache-2.0",
    "https://huggingface.co/PaddlePaddle", "apache-2.0.txt", null, Section.MODELS,
    "Downloaded from PaddlePaddle's Hugging Face repositories."),
  Component("fastText language identification (lid.176)", "", "CC-BY-SA-3.0",
    "https://fasttext.cc/docs/en/language-identification.html", "cc-by-sa-3.0.txt",
    TextSource.Inline(ccBySa), Section.MODELS, "Downloaded unmodified from Meta."),
)

fun normalized(text: String) = text.replace("\r\n", "\n").trimEnd() + "\n"

fun resolveText(source: TextSource): String = when (source) {
  is TextSource.Imported -> {
    val f = importedDir.resolve(source.path)
    if (!f.isFile) throw GradleException("Missing $f — run a native build first so import_remote clones it")
    val text = f.readText()
    normalized(source.lines?.let { r -> text.lines().slice(r.first - 1 until r.last).joinToString("\n") } ?: text)
  }
  is TextSource.Remote -> normalized(URI(source.url).toURL().readText())
  is TextSource.Inline -> normalized(source.text)
  is TextSource.Repo -> normalized(repoRoot.resolve(source.path).readText())
}

fun indexJson(): String = JsonOutput.prettyPrint(JsonOutput.toJson(mapOf(
  "sections" to Section.entries.map { mapOf("id" to it.name, "title" to it.title) },
  "mplSourceNotice" to "This app contains MPL-2.0 code. The Bergamot translator: upstream source at " +
    "https://github.com/mozilla/translations/tree/$translationsCommit/inference, with our modifications given in full " +
    "in the \"Source changes\" entry below. Eigen (unmodified), bundled in ONNX Runtime $ortVersion: " +
    "https://gitlab.com/libeigen/eigen.",
  "components" to components.map { c ->
    mapOf(
      "name" to c.name, "version" to c.version, "license" to c.license, "url" to c.url,
      "file" to c.file, "section" to c.section.name, "note" to (c.note ?: ""),
    )
  },
))) + "\n"

fun checkTable() {
  val files = components.map { it.file }.toSet()
  val produced = components.filter { it.source != null }.map { it.file }
  val dup = produced.groupBy { it }.filter { it.value.size > 1 }.keys
  if (dup.isNotEmpty()) throw GradleException("Several sources write $dup")
  val orphan = files - produced.toSet()
  if (orphan.isNotEmpty()) throw GradleException("No source produces $orphan")
}

tasks.register("updateLicenses") {
  group = "licenses"
  description = "Regenerates src/main/assets/licenses from the pinned sources."
  doLast {
    checkTable()
    assetsDir.mkdirs()
    components.filter { it.source != null }.forEach { c -> assetsDir.resolve(c.file).writeText(resolveText(c.source!!)) }
    assetsDir.resolve("index.json").writeText(indexJson())
    val keep = components.map { it.file }.toSet() + "index.json"
    assetsDir.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
    logger.lifecycle("Wrote ${keep.size} files to $assetsDir")
  }
}

tasks.register("verifyLicenses") {
  group = "verification"
  description = "Fails if the committed license notices are out of date."
  doLast {
    checkTable()
    val problems = mutableListOf<String>()
    if (assetsDir.resolve("index.json").takeIf { it.isFile }?.readText() != indexJson()) {
      problems += "index.json is out of date"
    }
    components.forEach { c ->
      if (!assetsDir.resolve(c.file).isFile) problems += "${c.file} is missing"
    }
    if (!importedDir.resolve(tr).isDirectory && importedDir.isDirectory) {
      problems += "_imported has no $tr: is translationsTag in sync with libbergamot.cmake?"
    }
    // Imported texts are compared when available (after a native build); remote ones are not
    // fetched here so `check` works offline. ortVersion is part of index.json, so a version bump
    // still fails above until updateLicenses is re-run.
    components.forEach { c ->
      val path = when (val src = c.source) {
        is TextSource.Imported -> src.path.takeIf { importedDir.resolve(it).isFile }
        is TextSource.Repo -> src.path
        else -> null
      } ?: return@forEach
      if (assetsDir.resolve(c.file).takeIf { it.isFile }?.readText() != resolveText(c.source!!)) {
        problems += "${c.file} differs from $path"
      }
    }
    if (problems.isNotEmpty()) {
      throw GradleException("License notices are stale:\n  " + problems.joinToString("\n  ") +
        "\nRun ./gradlew :app:updateLicenses and commit the result.")
    }
  }
}

tasks.matching { it.name == "check" }.configureEach { dependsOn("verifyLicenses") }
