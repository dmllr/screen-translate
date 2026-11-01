package com.eillance.screentranslate.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.util.Log
import com.eillance.screentranslate.models.ModelPack
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** A captured frame as raw ARGB_8888 pixels. */
class Frame(val argb: IntArray, val width: Int, val height: Int)

data class OcrOptions(
  /** Longest side fed to the detector. Higher finds smaller text but is slower. */
  val detectorMaxSide: Int = 1280,
  /** Lines below this confidence from the core recognizer are retried with add-on recognizers. */
  val retryBelowConfidence: Float = 0.80f,
  /** Lines below this final confidence are dropped. */
  val minConfidence: Float = 0.50f,
  /** Lines shorter than this (in frame pixels) are ignored. */
  val minLineHeight: Int = 8,
  val threads: Int = 4,
)

/**
 * PaddleOCR (PP-OCRv6 / v5) running on ONNX Runtime: DB text detector + one or more CTC recognizers.
 * Not thread-safe; callers serialize access (the pipeline runs on a single worker).
 */
class PaddleOcr(
  detectorDir: File,
  recognizers: List<Pair<ModelPack, File>>,
  private val options: OcrOptions = OcrOptions(),
) : Closeable {

  // Errors only: ORT otherwise logs warnings about its (disabled) Android telemetry at startup.
  private val env = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR, "ScreenTranslate").also {
    // The uploader's Android entry point is stripped from the manifest; this also turns the
    // native event collection off. Nothing may leave the device.
    runCatching { it.setTelemetry(false) }
  }
  private val sessionOptions = OrtSession.SessionOptions().apply {
    setIntraOpNumThreads(options.threads)
    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
  }
  private val detector: OrtSession = env.createSession(File(detectorDir, "inference.onnx").absolutePath, sessionOptions)
  private val post = DbPostProcess()

  private class Recognizer(val pack: ModelPack, val session: OrtSession, val decoder: CtcDecoder)

  /** First entry is the core recognizer; the rest are add-ons tried on low-confidence lines. */
  private val recognizers: List<Recognizer> = recognizers.map { (pack, dir) ->
    val session = env.createSession(File(dir, "inference.onnx").absolutePath, sessionOptions)
    val dict = CharacterDict.parse(File(dir, "inference.yml").readText())
    val classes = (session.outputInfo.values.first().info as TensorInfo).shape.last().toInt()
    Recognizer(pack, session, CtcDecoder(dict, classes, pack.rightToLeft))
  }

  init {
    require(this.recognizers.isNotEmpty()) { "At least one recognizer is required" }
  }

  fun recognize(frame: Frame): List<OcrLine> {
    val boxes = detect(frame)
    if (boxes.isEmpty()) return emptyList()

    val vertical = boxes.map { it.height >= 1.5f * it.width && it.height > 2 * options.minLineHeight }
    val core = recognizers.first()
    val results = Array(boxes.size) { CtcDecoder.Result("", 0f) }
    recognizeBatch(core, frame, boxes, vertical, boxes.indices.toList(), results)

    if (recognizers.size > 1) {
      val retry = boxes.indices.filter { results[it].confidence < options.retryBelowConfidence }
      if (retry.isNotEmpty()) {
        for (addon in recognizers.drop(1)) {
          val alt = Array(boxes.size) { CtcDecoder.Result("", 0f) }
          recognizeBatch(addon, frame, boxes, vertical, retry, alt)
          for (i in retry) if (alt[i].confidence > results[i].confidence) results[i] = alt[i]
        }
      }
    }

    return boxes.indices
      .filter { results[it].text.isNotBlank() && results[it].confidence >= options.minConfidence }
      .map { OcrLine(boxes[it], results[it].text, results[it].confidence, vertical[it]) }
  }

  /** Text boxes in frame coordinates. */
  fun detect(frame: Frame): List<Box> {
    val scale = minOf(1f, options.detectorMaxSide.toFloat() / max(frame.width, frame.height))
    val dw = roundTo32(frame.width * scale)
    val dh = roundTo32(frame.height * scale)
    val input = ImageOps.detectorTensor(frame.argb, frame.width, frame.height, dw, dh)
    val prob = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, dh.toLong(), dw.toLong())).use { t ->
      detector.run(mapOf(detector.inputNames.first() to t)).use { res ->
        val out = res[0] as OnnxTensor
        val buf = out.floatBuffer
        FloatArray(buf.remaining()).also { buf.get(it) }
      }
    }
    val sx = frame.width.toFloat() / dw
    val sy = frame.height.toFloat() / dh
    return post.extract(prob, dw, dh)
      .map { it.scaled(sx, sy).clampTo(frame.width, frame.height) }
      .filter { minOf(it.width, it.height) >= options.minLineHeight }
  }

  private fun recognizeBatch(
    rec: Recognizer, frame: Frame, boxes: List<Box>, vertical: List<Boolean>,
    indices: List<Int>, out: Array<CtcDecoder.Result>,
  ) {
    fun ratio(i: Int): Float {
      val b = boxes[i]
      return if (vertical[i]) b.height.toFloat() / b.width else b.width.toFloat() / b.height
    }
    // Sorting by aspect ratio keeps padding small inside each batch (as PaddleOCR does).
    val order = indices.sortedBy(::ratio)
    for (chunk in order.chunked(BATCH)) {
      val maxRatio = max(BASE_RATIO, chunk.maxOf(::ratio))
      val batchWidth = ceil(REC_HEIGHT * maxRatio).toInt().coerceAtMost(MAX_REC_WIDTH)
      val data = FloatArray(chunk.size * 3 * REC_HEIGHT * batchWidth)
      chunk.forEachIndexed { k, i ->
        ImageOps.writeRecognizerSample(frame.argb, frame.width, frame.height, boxes[i], vertical[i], data, k, REC_HEIGHT, batchWidth)
      }
      val shape = longArrayOf(chunk.size.toLong(), 3, REC_HEIGHT.toLong(), batchWidth.toLong())
      try {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { t ->
          rec.session.run(mapOf(rec.session.inputNames.first() to t)).use { res ->
            val tensor = res[0] as OnnxTensor
            val dims = tensor.info.shape  // [N, T, C]
            val steps = dims[1].toInt()
            val classes = dims[2].toInt()
            val buf = tensor.floatBuffer
            val probs = FloatArray(buf.remaining()).also { buf.get(it) }
            chunk.forEachIndexed { k, i -> out[i] = rec.decoder.decode(probs, k * steps * classes, steps) }
          }
        }
      } catch (e: Exception) {
        Log.w(TAG, "recognizer ${rec.pack.id} failed on a batch", e)
      }
    }
  }

  override fun close() {
    detector.close()
    recognizers.forEach { it.session.close() }
    sessionOptions.close()
  }

  private fun roundTo32(v: Float): Int = max(32, (v / 32f).roundToInt() * 32)

  companion object {
    private const val TAG = "PaddleOcr"
    private const val REC_HEIGHT = 48
    private const val BASE_RATIO = 320f / 48f
    private const val MAX_REC_WIDTH = 3200
    private const val BATCH = 8
  }
}
