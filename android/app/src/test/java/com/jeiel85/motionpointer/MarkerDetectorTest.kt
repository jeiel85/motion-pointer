package com.jeiel85.motionpointer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MarkerDetectorTest {
    private val width = 640
    private val height = 480

    private fun imageWithMarkers(count: Int): ByteArray {
        val luma = ByteArray(width * height) { 30.toByte() } // 어두운 화면
        val destinations = MarkerLayout.destinations()
        val size = (MarkerLayout.SIZE * height).toInt()
        for (center in destinations.take(count)) {
            val cx = (center.x * width).toInt()
            val cy = (center.y * height).toInt()
            for (y in cy - size / 2..cy + size / 2) {
                for (x in cx - size / 2..cx + size / 2) {
                    if (x in 0 until width && y in 0 until height) luma[y * width + x] = 255.toByte()
                }
            }
        }
        return luma
    }

    @Test
    fun finds_four_markers_in_clockwise_order() {
        val markers = MarkerDetector().detect(imageWithMarkers(4), width, height, width)
            ?: throw AssertionError("마커를 찾지 못했습니다")

        val expected = MarkerLayout.destinations()
        assertEquals(4, markers.size)
        for (i in markers.indices) {
            assertTrue("marker $i x=${markers[i].x}", abs(markers[i].x - expected[i].x * width) < 4)
            assertTrue("marker $i y=${markers[i].y}", abs(markers[i].y - expected[i].y * height) < 4)
        }
    }

    @Test
    fun fewer_than_four_markers_is_tracking_loss() {
        assertNull(MarkerDetector().detect(imageWithMarkers(3), width, height, width))
    }

    @Test
    fun blank_screen_is_tracking_loss() {
        val blank = ByteArray(width * height) { 30.toByte() }
        assertNull(MarkerDetector().detect(blank, width, height, width))
    }
}
