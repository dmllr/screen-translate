package com.eillance.screentranslate.models

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** Plain HttpURLConnection downloader: no third-party HTTP stack, no analytics, no proxies. */
class Downloader(private val userAgent: String = "ScreenTranslate/0.1 (Android)") {

  fun fetchText(url: String): String = open(url).use { it.readBytes().toString(Charsets.UTF_8) }

  /**
   * Downloads [file] to [target] atomically (via a `.part` file) and verifies its SHA-256.
   * Gzip-compressed payloads are transparently inflated and the hash is computed over the
   * inflated bytes (registries such as Mozilla's hash the uncompressed file).
   */
  fun download(file: RemoteFile, target: File, onProgress: (Long) -> Unit) {
    val part = File(target.parentFile, target.name + ".part")
    part.delete()
    val digest = MessageDigest.getInstance("SHA-256")
    var read = 0L
    open(file.url).use { raw ->
      val input = maybeGunzip(BufferedInputStream(raw, BUFFER))
      part.outputStream().buffered(BUFFER).use { out ->
        val buf = ByteArray(BUFFER)
        var lastReport = 0L
        while (true) {
          val n = input.read(buf)
          if (n < 0) break
          out.write(buf, 0, n)
          digest.update(buf, 0, n)
          read += n
          if (read - lastReport > REPORT_EVERY) {
            onProgress(read)
            lastReport = read
          }
        }
      }
    }
    onProgress(read)
    val actual = digest.digest().toHex()
    if (file.sha256 != null && !actual.equals(file.sha256, ignoreCase = true)) {
      part.delete()
      throw ChecksumMismatchException(file.fileName)
    }
    if (target.exists()) target.delete()
    if (!part.renameTo(target)) throw IOException("Cannot move ${part.name} into place")
  }

  private fun open(url: String): InputStream {
    var current = URL(url)
    repeat(MAX_REDIRECTS) {
      val conn = current.openConnection() as HttpURLConnection
      conn.connectTimeout = 20_000
      conn.readTimeout = 60_000
      conn.instanceFollowRedirects = false
      conn.setRequestProperty("User-Agent", userAgent)
      when (val code = conn.responseCode) {
        in 200..299 -> return conn.inputStream
        in 300..399 -> {
          val location = conn.getHeaderField("Location") ?: throw IOException("Redirect without Location from $current")
          conn.disconnect()
          current = URL(current, location)
          if (current.protocol != "https") throw IOException("Refusing non-HTTPS redirect to $current")
        }
        else -> {
          conn.disconnect()
          throw IOException("HTTP $code for $current")
        }
      }
    }
    throw IOException("Too many redirects for $url")
  }

  private fun maybeGunzip(input: BufferedInputStream): InputStream {
    input.mark(2)
    val b0 = input.read()
    val b1 = input.read()
    input.reset()
    return if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(input, BUFFER) else input
  }

  companion object {
    private const val BUFFER = 64 * 1024
    private const val REPORT_EVERY = 256 * 1024L
    private const val MAX_REDIRECTS = 5

    fun sha256(file: File): String {
      val digest = MessageDigest.getInstance("SHA-256")
      file.inputStream().use { input ->
        val buf = ByteArray(BUFFER)
        while (true) {
          val n = input.read(buf)
          if (n < 0) break
          digest.update(buf, 0, n)
        }
      }
      return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
  }
}
