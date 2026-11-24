package com.jeiel85.motionpointer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class HomographyTest {
    private val corners = listOf(
        Point2(0.0, 0.0), Point2(640.0, 0.0), Point2(640.0, 480.0), Point2(0.0, 480.0),
    )

    @Test
    fun restores_affine_mapping() {
        val normalized = listOf(Point2(0.0, 0.0), Point2(1.0, 0.0), Point2(1.0, 1.0), Point2(0.0, 1.0))
        val homography = Homography.fromCorrespondences(corners, normalized) ?: throw AssertionError("호모그래피를 풀지 못했습니다")

        val center = homography.map(320.0, 240.0) ?: throw AssertionError("중심을 투영하지 못했습니다")
        assertEquals(0.5, center.x, 1e-6)
        assertEquals(0.5, center.y, 1e-6)

        val quarter = homography.map(160.0, 120.0) ?: throw AssertionError("사분점을 투영하지 못했습니다")
        assertEquals(0.25, quarter.x, 1e-6)
        assertEquals(0.25, quarter.y, 1e-6)
    }

    @Test
    fun restores_projective_mapping() {
        // 알려진 원근 변환으로 네 모서리를 옮긴 뒤, 같은 변환을 복원하는지 확인한다.
        val known = doubleArrayOf(1.4, 0.2, 30.0, 0.1, 1.1, -20.0, 0.0008, -0.0006, 1.0)
        fun transform(p: Point2): Point2 {
            val w = known[6] * p.x + known[7] * p.y + known[8]
            return Point2((known[0] * p.x + known[1] * p.y + known[2]) / w, (known[3] * p.x + known[4] * p.y + known[5]) / w)
        }

        val homography = Homography.fromCorrespondences(corners, corners.map(::transform))
            ?: throw AssertionError("원근 변환을 복원하지 못했습니다")
        val probe = Point2(213.0, 177.0)
        val expected = transform(probe)
        val actual = homography.map(probe.x, probe.y) ?: throw AssertionError("탐침점을 투영하지 못했습니다")

        assertEquals(expected.x, actual.x, 1e-4)
        assertEquals(expected.y, actual.y, 1e-4)
    }

    @Test
    fun degenerate_points_are_rejected() {
        val duplicated = listOf(Point2(0.0, 0.0), Point2(0.0, 0.0), Point2(1.0, 0.0), Point2(0.0, 1.0))
        val destination = listOf(Point2(0.0, 0.0), Point2(1.0, 0.0), Point2(1.0, 1.0), Point2(0.0, 1.0))
        assertNull(Homography.fromCorrespondences(duplicated, destination))
    }
}

class AbsolutePointerTest {
    private val corners = listOf(
        Point2(0.0, 0.0), Point2(640.0, 0.0), Point2(640.0, 480.0), Point2(0.0, 480.0),
    )
    private val normalized = listOf(Point2(0.0, 0.0), Point2(1.0, 0.0), Point2(1.0, 1.0), Point2(0.0, 1.0))

    private fun centeredPointer(): AbsolutePointer {
        val pointer = AbsolutePointer(focalFactor = 0.7)
        val homography = Homography.fromCorrespondences(corners, normalized) ?: throw AssertionError("호모그래피 실패")
        assertTrue(pointer.onCamera(homography, 640, 480))
        return pointer
    }

    @Test
    fun camera_center_maps_to_screen_center() {
        val position = centeredPointer().position() ?: throw AssertionError("추적 위치가 없습니다")
        assertEquals(0.5, position.x, 1e-6)
        assertEquals(0.5, position.y, 1e-6)
    }

    @Test
    fun turning_right_moves_the_aim_right() {
        val pointer = centeredPointer()
        pointer.onGyro(yawDeg = 10.0, pitchDeg = 0.0)
        val position = pointer.position() ?: throw AssertionError("추적 위치가 없습니다")

        assertTrue("x=${position.x}", position.x > 0.55)
        assertEquals(0.5, position.y, 1e-6)
    }

    @Test
    fun raising_the_aim_moves_up() {
        val pointer = centeredPointer()
        pointer.onGyro(yawDeg = 0.0, pitchDeg = 10.0)
        val position = pointer.position() ?: throw AssertionError("추적 위치가 없습니다")

        assertTrue("y=${position.y}", position.y < 0.45)
        assertEquals(0.5, position.x, 1e-6)
    }

    @Test
    fun camera_frame_corrects_gyro_drift() {
        val pointer = centeredPointer()
        repeat(20) { pointer.onGyro(yawDeg = 5.0, pitchDeg = 0.0) }
        val drifted = pointer.position() ?: throw AssertionError("추적 위치가 없습니다")
        assertTrue("drift=${drifted.x}", drifted.x > 0.9)

        val homography = Homography.fromCorrespondences(corners, normalized) ?: throw AssertionError("호모그래피 실패")
        assertTrue(pointer.onCamera(homography, 640, 480))
        val corrected = pointer.position() ?: throw AssertionError("추적 위치가 없습니다")
        assertTrue("corrected=${corrected.x}", corrected.x < drifted.x)
        assertTrue("corrected=${corrected.x}", abs(corrected.x - 0.5) < 0.2)
    }

    @Test
    fun lost_tracking_stops_output_until_next_camera_frame() {
        val pointer = centeredPointer()
        pointer.lost()
        assertNull(pointer.position())
        pointer.onGyro(yawDeg = 10.0, pitchDeg = 0.0)
        assertNull(pointer.position())

        val homography = Homography.fromCorrespondences(corners, normalized) ?: throw AssertionError("호모그래피 실패")
        assertTrue(pointer.onCamera(homography, 640, 480))
        assertNotNull(pointer.position())
    }

    @Test
    fun position_stays_inside_the_screen() {
        val pointer = centeredPointer()
        repeat(100) { pointer.onGyro(yawDeg = 20.0, pitchDeg = 20.0) }
        val position = pointer.position() ?: throw AssertionError("추적 위치가 없습니다")
        assertTrue(position.x in 0.0..1.0)
        assertTrue(position.y in 0.0..1.0)
    }
}
