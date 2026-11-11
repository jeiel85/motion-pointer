package com.jeiel85.motionpointer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class PointerMapperTest {
    private val deg = PI / 180
    private val up = Vec3(0.0, 0.0, 1.0)
    private val stepNs = 10_000_000L // 100Hz

    /** 폰을 눕혀 위쪽 끝(+Y)이 앞(세계 +Y)을 가리키는 자세. 기기 좌표 = 세계 좌표. */
    private val flat = Quat.IDENTITY

    /** 폰을 세워 화면이 사용자 쪽, 뒷면 카메라가 앞을 가리키는 자세(기기 X축 둘레 +90°). */
    private val upright = Quat.fromAxisAngle(Vec3(1.0, 0.0, 0.0), PI / 2)

    private fun sweep(start: Quat, worldAxis: Vec3, degPerSec: Double, seconds: Double, mapper: PointerMapper = PointerMapper()): Pair<Int, Int> {
        var dx = 0
        var dy = 0
        val steps = (seconds * 100).toInt()
        for (i in 0..steps) {
            val angle = degPerSec * i * 0.01 * deg
            // 세계 축 둘레 회전 = 왼쪽에서 곱한다.
            val q = Quat.fromAxisAngle(worldAxis, angle) * start
            val d = mapper.update(q, i * stepNs)
            dx += d.dx
            dy += d.dy
        }
        return dx to dy
    }

    @Test
    fun turning_right_moves_right_in_both_grips() {
        for (grip in listOf(flat, upright)) {
            val (dx, dy) = sweep(grip, up, degPerSec = -30.0, seconds = 1.0) // 위에서 볼 때 시계 방향
            assertTrue("dx=$dx", dx in 300..1300)
            assertEquals(0, dy)
        }
    }

    @Test
    fun raising_the_pointer_moves_up_in_both_grips() {
        // 앞을 가리키던 끝을 들어 올리는 회전 = 세계 +X(오른쪽) 축 둘레 + 회전
        for (grip in listOf(flat, upright)) {
            val (dx, dy) = sweep(grip, Vec3(1.0, 0.0, 0.0), degPerSec = 30.0, seconds = 1.0)
            assertEquals(0, dx)
            assertTrue("dy=$dy", dy < -300)
        }
    }

    @Test
    fun yaw_ignores_how_much_the_phone_is_rolled() {
        // 손목을 20° 비튼 상태에서도 좌우 회전은 순수한 가로 이동이어야 한다.
        val rolled = Quat.fromAxisAngle(Vec3(0.0, 1.0, 0.0), 20 * deg)
        val (dx, dy) = sweep(rolled, up, degPerSec = -30.0, seconds = 1.0)
        assertTrue(dx > 300)
        assertEquals(0, dy)
    }

    @Test
    fun faster_motion_uses_higher_gain() {
        val slow = sweep(flat, up, degPerSec = -15.0, seconds = 2.0).first   // 30°
        val fast = sweep(flat, up, degPerSec = -300.0, seconds = 0.1).first  // 30°
        assertTrue("slow=$slow fast=$fast", fast > slow * 2)
    }

    @Test
    fun tiny_drift_is_suppressed() {
        val (dx, _) = sweep(flat, up, degPerSec = -0.5, seconds = 2.0)
        assertTrue("dx=$dx", dx in 0..3)
    }

    @Test
    fun sensitivity_scales_movement() {
        val normal = sweep(flat, up, -30.0, 1.0).first
        val double = sweep(flat, up, -30.0, 1.0, PointerMapper(PointerSettings(sensitivity = 2.0))).first
        assertTrue("normal=$normal double=$double", double in (normal * 2 - 2)..(normal * 2 + 2))
    }

    @Test
    fun gap_and_reset_do_not_jump() {
        val mapper = PointerMapper()
        mapper.update(flat, 0)
        val afterGap = mapper.update(Quat.fromAxisAngle(up, -40 * deg), 1_000_000_000)
        assertEquals(PointerDelta.ZERO, afterGap)

        mapper.reset()
        val first = mapper.update(Quat.fromAxisAngle(up, -80 * deg), 1_010_000_000)
        assertEquals(PointerDelta.ZERO, first)
    }
}

class ProtocolsTest {
    @Test
    fun pc_frames_match_spec() {
        assertBytes(intArrayOf(0x01, 1, 1), PointerProtocol.hello())
        assertBytes(intArrayOf(0x02, 4, 0x05, 0x00, 0xFE, 0xFF), PointerProtocol.move(5, -2))
        assertBytes(intArrayOf(0x03, 1, 0x03), PointerProtocol.buttons(Buttons.LEFT or Buttons.RIGHT))
        assertBytes(intArrayOf(0x04, 1, 0xFF), PointerProtocol.wheel(-1))
    }

    @Test
    fun pc_frames_clamp_out_of_range_values() {
        assertBytes(intArrayOf(0x02, 4, 0xFF, 0x7F, 0x00, 0x80), PointerProtocol.move(100_000, -100_000))
        assertBytes(intArrayOf(0x04, 1, 0x7F), PointerProtocol.wheel(500))
        assertBytes(intArrayOf(0x03, 1, 0x07), PointerProtocol.buttons(0xFF))
    }

    @Test
    fun hid_report_layout() {
        assertBytes(intArrayOf(0x01, 0x10, 0x00, 0xF0, 0xFF, 0x02), HidMouse.report(Buttons.LEFT, 16, -16, 2))
        assertBytes(intArrayOf(0x00, 0xFF, 0x7F, 0x01, 0x80, 0x81), HidMouse.report(0, 99_999, -99_999, -300))
    }

    @Test
    fun hid_descriptor_declares_six_byte_report() {
        // 버튼 3비트 + 패딩 5비트 + X·Y 16비트 2개 + 휠 8비트 = 48비트 = 6바이트
        assertEquals(6, HidMouse.report(0, 0, 0, 0).size)
        assertEquals(0xC0.toByte(), HidMouse.DESCRIPTOR.last())
    }

    private fun assertBytes(expected: IntArray, actual: ByteArray) {
        assertEquals(expected.map { it.toByte() }, actual.toList())
    }
}
