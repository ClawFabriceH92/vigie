package com.fabrice.vigie

import com.fabrice.vigie.camera.MotionDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionDetectorTest {

    private fun frameOf(width: Int, height: Int, value: Int, rowStride: Int = width): MotionDetector.GrayFrame {
        val y = ByteArray(rowStride * height)
        for (i in y.indices) y[i] = value.toByte()
        return MotionDetector.downsample(y, width, height, rowStride, cols = 8, rows = 6)
    }

    @Test
    fun `downsample produit 48 blocs`() {
        val f = frameOf(16, 12, 100)
        assertEquals(8 * 6, f.blocks.size)
    }

    @Test
    fun `downsample valeur uniforme`() {
        val f = frameOf(16, 12, 100)
        for (b in f.blocks) assertEquals(100f, b, 0.01f)
    }

    @Test
    fun `downsample gère le padding rowStride`() {
        val width = 16
        val height = 12
        val rowStride = 32 // padding de 16 octets par ligne
        val y = ByteArray(rowStride * height)
        for (row in 0 until height) {
            for (col in 0 until width) {
                y[row * rowStride + col] = 200.toByte()
            }
        }
        val f = MotionDetector.downsample(y, width, height, rowStride, cols = 8, rows = 6)
        for (b in f.blocks) assertEquals(200f, b, 0.01f)
    }

    @Test
    fun `diffScore identique vaut zero`() {
        val a = frameOf(16, 12, 100)
        val b = frameOf(16, 12, 100)
        assertEquals(0f, MotionDetector.diffScore(a, b), 0.01f)
    }

    @Test
    fun `diffScore different est positif`() {
        val a = frameOf(16, 12, 100)
        val b = frameOf(16, 12, 150)
        val score = MotionDetector.diffScore(a, b)
        assertEquals(50f, score, 0.5f)
    }

    @Test
    fun `isMotion seuil`() {
        assertTrue(MotionDetector.isMotion(20f, 14))
        assertFalse(MotionDetector.isMotion(10f, 14))
    }

    @Test
    fun `hysteresis garde le trigger sous le seuil`() {
        // Déclenché : reste actif tant que score > seuil * 0.6
        assertTrue(MotionDetector.isMotionHysteresis(10f, 14, wasTriggered = true))
        assertFalse(MotionDetector.isMotionHysteresis(7f, 14, wasTriggered = true))
        // Non déclenché : il faut dépasser le seuil
        assertFalse(MotionDetector.isMotionHysteresis(10f, 14, wasTriggered = false))
    }
}
