package com.eillance.screentranslate.ocr

/**
 * Pixel → tensor conversions on raw ARGB_8888 int arrays (no Bitmap, so it is JVM-testable).
 * All outputs are planar CHW in **BGR** channel order, which is what PaddleOCR models are trained on.
 */
object ImageOps {

  /** Detector input: bilinear resize of the whole frame, ImageNet mean/std (Paddle's BGR quirk). */
  fun detectorTensor(argb: IntArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): FloatArray {
    val out = FloatArray(3 * dstW * dstH)
    val plane = dstW * dstH
    // Paddle applies mean/std in list order to BGR channels.
    val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
    val invStd = floatArrayOf(1f / 0.229f, 1f / 0.224f, 1f / 0.225f)
    sample(argb, srcW, srcH, 0f, 0f, srcW.toFloat(), srcH.toFloat(), false, dstW, dstH) { x, y, b, g, r ->
      val i = y * dstW + x
      out[i] = (b / 255f - mean[0]) * invStd[0]
      out[plane + i] = (g / 255f - mean[1]) * invStd[1]
      out[2 * plane + i] = (r / 255f - mean[2]) * invStd[2]
    }
    return out
  }

  /**
   * Recognizer input for one line: crops [box] from the frame (rotating vertical columns 90° CCW
   * so they read left-to-right), resizes to [height] keeping aspect ratio, and writes it into
   * [out] at sample [index] of a batch padded to [batchWidth]. Normalization is (x/255 - 0.5) / 0.5.
   * @return the width actually written (the rest stays zero padding).
   */
  fun writeRecognizerSample(
    argb: IntArray, srcW: Int, srcH: Int, box: Box, rotate: Boolean,
    out: FloatArray, index: Int, height: Int, batchWidth: Int,
  ): Int {
    val cropW = if (rotate) box.height else box.width
    val cropH = if (rotate) box.width else box.height
    val w = recognizerWidth(cropW, cropH, height, batchWidth)
    val plane = height * batchWidth
    val base = index * 3 * plane
    sample(
      argb, srcW, srcH, box.left.toFloat(), box.top.toFloat(), box.width.toFloat(), box.height.toFloat(),
      rotate, w, height,
    ) { x, y, b, g, r ->
      val i = base + y * batchWidth + x
      out[i] = b / 127.5f - 1f
      out[plane + i] = g / 127.5f - 1f
      out[2 * plane + i] = r / 127.5f - 1f
    }
    return w
  }

  fun recognizerWidth(cropW: Int, cropH: Int, height: Int, maxWidth: Int): Int {
    val ratio = cropW.toFloat() / maxOf(1, cropH)
    return (kotlin.math.ceil(height * ratio).toInt()).coerceIn(1, maxWidth)
  }

  /**
   * Bilinear sampling of the source region (rx, ry, rw, rh) into a dstW x dstH grid.
   * With [rotateCcw] the region is rotated 90° counter-clockwise first (dst x runs down the source).
   */
  private inline fun sample(
    argb: IntArray, srcW: Int, srcH: Int,
    rx: Float, ry: Float, rw: Float, rh: Float, rotateCcw: Boolean,
    dstW: Int, dstH: Int,
    write: (x: Int, y: Int, b: Int, g: Int, r: Int) -> Unit,
  ) {
    for (y in 0 until dstH) {
      for (x in 0 until dstW) {
        // Normalized position inside the destination.
        val u = (x + 0.5f) / dstW
        val v = (y + 0.5f) / dstH
        // Rotating CCW: destination x follows the source top->bottom, destination y follows source right->left.
        val sx = if (rotateCcw) rx + (1f - v) * rw else rx + u * rw
        val sy = if (rotateCcw) ry + u * rh else ry + v * rh
        val fx = (sx - 0.5f).coerceIn(0f, (srcW - 1).toFloat())
        val fy = (sy - 0.5f).coerceIn(0f, (srcH - 1).toFloat())
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val x1 = minOf(x0 + 1, srcW - 1)
        val y1 = minOf(y0 + 1, srcH - 1)
        val ax = fx - x0
        val ay = fy - y0
        val p00 = argb[y0 * srcW + x0]
        val p10 = argb[y0 * srcW + x1]
        val p01 = argb[y1 * srcW + x0]
        val p11 = argb[y1 * srcW + x1]
        write(
          x, y,
          bilerp(p00, p10, p01, p11, 0, ax, ay),
          bilerp(p00, p10, p01, p11, 8, ax, ay),
          bilerp(p00, p10, p01, p11, 16, ax, ay),
        )
      }
    }
  }

  @PublishedApi
  internal fun bilerp(p00: Int, p10: Int, p01: Int, p11: Int, shift: Int, ax: Float, ay: Float): Int {
    val c00 = (p00 shr shift) and 0xFF
    val c10 = (p10 shr shift) and 0xFF
    val c01 = (p01 shr shift) and 0xFF
    val c11 = (p11 shr shift) and 0xFF
    val top = c00 + (c10 - c00) * ax
    val bottom = c01 + (c11 - c01) * ax
    return (top + (bottom - top) * ay).toInt()
  }
}
