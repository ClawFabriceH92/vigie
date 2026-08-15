package com.fabrice.vigie.camera

import kotlin.math.abs

/**
 * Détection de mouvement par différence de luminance entre frames.
 *
 * Logique PURE (testable en JUnit) : chaque frame est réduite à une grille
 * de moyennes de blocs ; le score de mouvement est la moyenne des différences
 * absolues par bloc entre la frame courante et la précédente.
 */
object MotionDetector {

    const val DEFAULT_COLS = 8
    const val DEFAULT_ROWS = 6

    /** Frame réduite : moyennes de luminance par bloc. */
    data class GrayFrame(
        val width: Int,
        val height: Int,
        val cols: Int,
        val rows: Int,
        val blocks: FloatArray,
    )

    /**
     * Réduit un buffer de luminance Y (plan Y d'une frame YUV_420_888) en
     * moyennes par blocs. [rowStride] est le stride réel du buffer source.
     */
    fun downsample(
        y: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        cols: Int = DEFAULT_COLS,
        rows: Int = DEFAULT_ROWS,
    ): GrayFrame {
        require(width >= cols && height >= rows) { "Frame trop petite pour la grille" }
        val blockW = width / cols
        val blockH = height / rows
        val blocks = FloatArray(cols * rows)
        for (r in 0 until rows) {
            val startY = r * blockH
            for (c in 0 until cols) {
                val startX = c * blockW
                var sum = 0L
                for (yy in startY until startY + blockH) {
                    var idx = yy * rowStride + startX
                    val endX = startX + blockW
                    while (idx < yy * rowStride + endX) {
                        sum += y[idx].toInt() and 0xFF
                        idx++
                    }
                }
                blocks[r * cols + c] = sum.toFloat() / (blockW * blockH)
            }
        }
        return GrayFrame(width, height, cols, rows, blocks)
    }

    /** Score de mouvement : moyenne des différences absolues par bloc (0..255). */
    fun diffScore(current: GrayFrame, previous: GrayFrame): Float {
        require(current.blocks.size == previous.blocks.size)
        var sum = 0f
        for (i in current.blocks.indices) {
            sum += abs(current.blocks[i] - previous.blocks[i])
        }
        return sum / current.blocks.size
    }

    /** Décision simple : mouvement si le score dépasse le seuil. */
    fun isMotion(score: Float, threshold: Int): Boolean = score > threshold

    /**
     * Hystérésis : une fois déclenché, le mouvement reste actif tant que le
     * score reste au-dessus de [threshold] * [releaseFactor] (évite le
     * clignotement au seuil).
     */
    fun isMotionHysteresis(score: Float, threshold: Int, wasTriggered: Boolean, releaseFactor: Float = 0.6f): Boolean {
        val releaseThreshold = threshold * releaseFactor
        return if (wasTriggered) score > releaseThreshold else score > threshold
    }
}
