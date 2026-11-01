package com.eillance.screentranslate.models

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

sealed interface PackState {
  data object NotInstalled : PackState
  data class Downloading(val downloaded: Long, val total: Long) : PackState {
    val fraction: Float get() = if (total <= 0) 0f else (downloaded.toFloat() / total).coerceIn(0f, 1f)
  }
  data class Installed(val version: String) : PackState
  data class Failed(val message: String) : PackState
}

/** Files of an installed translation direction, ready to build a Bergamot config from. */
data class InstalledTranslation(
  val from: String,
  val to: String,
  val model: File,
  val lex: File,
  val vocabs: List<File>,
)

/**
 * Owns all model files under `filesDir/models/<pack id>/`. A pack counts as installed only once
 * `pack.json` is written, which happens after every file was downloaded and verified.
 */
class ModelRepository(context: Context, private val downloader: Downloader = Downloader()) {

  private val root = File(context.filesDir, "models").apply { mkdirs() }
  private val mozillaCache = File(root, "mozilla-records.json")

  private val _translationPacks = MutableStateFlow<List<ModelPack>>(emptyList())
  /** Translation directions available from Mozilla (empty until [refreshCatalog] succeeds once). */
  val translationPacks: StateFlow<List<ModelPack>> = _translationPacks.asStateFlow()

  private val _states = MutableStateFlow<Map<String, PackState>>(emptyMap())
  val states: StateFlow<Map<String, PackState>> = _states.asStateFlow()

  private val manifests = ConcurrentHashMap<String, JSONObject>()

  init {
    scanInstalled()  // cheap (a few directories) and needed synchronously
    // Parsing the cached Mozilla catalog (~400 kB JSON) on the main thread costs startup frames.
    thread(name = "catalog-load", isDaemon = true) { loadCachedCatalog() }
  }

  fun packDir(id: String) = File(root, id)

  fun isInstalled(id: String) = _states.value[id] is PackState.Installed

  fun missingRequired(): List<ModelPack> =
    StaticCatalog.required.filterNot(::isInstalled).map { id -> StaticCatalog.all.first { it.id == id } }

  fun installedRecognizers(): List<ModelPack> = StaticCatalog.recognizers.filter { isInstalled(it.id) }

  fun findPack(id: String): ModelPack? =
    StaticCatalog.all.firstOrNull { it.id == id } ?: _translationPacks.value.firstOrNull { it.id == id }

  /** Returns the installed files for `from -> to`, or null if that direction is not installed. */
  fun installedTranslation(from: String, to: String): InstalledTranslation? {
    val id = ModelPack.translationId(from, to)
    if (!isInstalled(id)) return null
    val manifest = manifests[id] ?: return null
    val dir = packDir(id)
    val roles = manifest.getJSONObject("roles")
    var model: File? = null
    var lex: File? = null
    var vocab: File? = null
    var src: File? = null
    var trg: File? = null
    for (name in roles.keys()) {
      val f = File(dir, name)
      when (roles.getString(name)) {
        "model" -> model = f
        "lex" -> lex = f
        "vocab" -> vocab = f
        "srcvocab" -> src = f
        "trgvocab" -> trg = f
      }
    }
    val vocabs = when {
      vocab != null -> listOf(vocab, vocab)
      src != null && trg != null -> listOf(src, trg)
      else -> return null
    }
    return InstalledTranslation(from, to, model ?: return null, lex ?: return null, vocabs)
  }

  fun installedTranslationPairs(): List<Pair<String, String>> =
    _states.value.filter { it.value is PackState.Installed && it.key.startsWith("translate-") }
      .mapNotNull { manifests[it.key] }
      .map { it.getString("from") to it.getString("to") }

  suspend fun refreshCatalog(): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
      val json = downloader.fetchText(MozillaCatalog.RECORDS_URL)
      val packs = MozillaCatalog.buildPacks(MozillaCatalog.parse(json))
      check(packs.isNotEmpty()) { "Mozilla catalog is empty" }
      mozillaCache.writeText(json)
      _translationPacks.value = packs
    }.onFailure { Log.w(TAG, "catalog refresh failed", it) }
  }

  suspend fun install(pack: ModelPack): Result<Unit> = withContext(Dispatchers.IO) {
    val dir = packDir(pack.id)
    setState(pack.id, PackState.Downloading(0, pack.totalSize))
    runCatching {
      dir.mkdirs()
      File(dir, MANIFEST).delete()
      var done = 0L
      for (file in pack.files) {
        val target = File(dir, file.fileName)
        if (target.isFile && target.length() == file.size && file.sha256 != null &&
          Downloader.sha256(target).equals(file.sha256, ignoreCase = true)
        ) {
          done += file.size
          continue
        }
        val base = done
        downloader.download(file, target) { bytes ->
          setState(pack.id, PackState.Downloading(base + bytes, pack.totalSize))
        }
        done += file.size
      }
      val manifest = JSONObject()
        .put("id", pack.id)
        .put("version", pack.version)
        .put("files", JSONArray(pack.files.map { it.fileName }))
        .put("roles", JSONObject(pack.fileRoles))
      pack.from?.let { manifest.put("from", it) }
      pack.to?.let { manifest.put("to", it) }
      File(dir, MANIFEST).writeText(manifest.toString())
      manifests[pack.id] = manifest
      setState(pack.id, PackState.Installed(pack.version))
    }.onFailure {
      Log.w(TAG, "install ${pack.id} failed", it)
      setState(pack.id, PackState.Failed(it.message ?: it.javaClass.simpleName))
    }
  }

  fun delete(id: String) {
    packDir(id).deleteRecursively()
    manifests.remove(id)
    setState(id, PackState.NotInstalled)
  }

  private fun setState(id: String, state: PackState) = _states.update { it + (id to state) }

  private fun scanInstalled() {
    val found = HashMap<String, PackState>()
    root.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
      val manifestFile = File(dir, MANIFEST)
      if (!manifestFile.isFile) return@forEach
      try {
        val json = JSONObject(manifestFile.readText())
        val files = json.getJSONArray("files")
        val complete = (0 until files.length()).all { File(dir, files.getString(it)).isFile }
        if (complete) {
          manifests[dir.name] = json
          found[dir.name] = PackState.Installed(json.optString("version"))
        }
      } catch (e: Exception) {
        Log.w(TAG, "bad manifest in ${dir.name}", e)
      }
    }
    _states.value = found
  }

  private fun loadCachedCatalog() {
    if (!mozillaCache.isFile) return
    try {
      _translationPacks.value = MozillaCatalog.buildPacks(MozillaCatalog.parse(mozillaCache.readText()))
    } catch (e: Exception) {
      Log.w(TAG, "cached catalog unreadable", e)
    }
  }

  companion object {
    private const val TAG = "ModelRepository"
    private const val MANIFEST = "pack.json"
  }
}

class ChecksumMismatchException(file: String) : IOException("Checksum mismatch for $file")
