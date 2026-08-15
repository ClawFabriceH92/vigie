package com.fabrice.vigie.camera

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Extraction de la luminance et conversion JPEG depuis des buffers YUV_420_888.
 * Signatures basées sur ByteBuffer (compatibles CameraX ImageProxy.PlaneProxy
 * et android.media.Image.Plane) → logique testable en JUnit.
 */
object FrameExtractor {

    /**
     * Copie un plan de luminance Y dans un ByteArray de taille
     * rowStride * height (padding compris). Utiliser rowStride comme stride
     * dans MotionDetector.downsample.
     */
    fun extractY(buffer: ByteBuffer, rowStride: Int, height: Int): ByteArray {
        val y = ByteArray(rowStride * height)
        buffer.rewind()
        buffer.get(y)
        return y
    }

    /**
     * Convertit des plans Y/U/V (YUV_420_888, potentiellement avec padding)
     * en NV21 (ByteArray width*height*3/2), prêt pour android.graphics.YuvImage.
     */
    fun yuv420ToNv21(
        yBuffer: ByteBuffer,
        uBuffer: ByteBuffer,
        vBuffer: ByteBuffer,
        width: Int,
        height: Int,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int,
    ): ByteArray {
        val nv21 = ByteArray(width * height * 3 / 2)

        // Y : copier chaque ligne de width octets (rowStride >= width)
        var pos = 0
        for (row in 0 until height) {
            yBuffer.position(row * yRowStride)
            yBuffer.get(nv21, pos, width)
            pos += width
        }

        // UV : NV21 intercale V,U (semi-planar), 1 pixel UV pour 4 Y
        val uvRows = height / 2
        var uvPos = width * height
        for (row in 0 until uvRows) {
            val yRow = row * uvRowStride
            for (col in 0 until width / 2) {
                val idx = yRow + col * uvPixelStride
                nv21[uvPos++] = vBuffer.get(idx).toByte()
                nv21[uvPos++] = uBuffer.get(idx).toByte()
            }
        }
        return nv21
    }

    /** NV21 → JPEG. Retourne null si l'encodage échoue. */
    fun nv21ToJpeg(nv21: ByteArray, width: Int, height: Int, quality: Int = 55): ByteArray? {
        val yuv = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        if (!yuv.compressToJpeg(android.graphics.Rect(0, 0, width, height), quality, out)) return null
        return out.toByteArray()
    }
}
