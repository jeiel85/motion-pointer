package com.jeiel85.motionpointer

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    val length: Double get() = sqrt(dot(this))
}

/** 해밀턴 곱 규약의 쿼터니언. 안드로이드 회전 벡터는 기기 좌표 → 세계 좌표 회전이다. */
data class Quat(val w: Double, val x: Double, val y: Double, val z: Double) {
    operator fun times(b: Quat) = Quat(
        w * b.w - x * b.x - y * b.y - z * b.z,
        w * b.x + x * b.w + y * b.z - z * b.y,
        w * b.y - x * b.z + y * b.w + z * b.x,
        w * b.z + x * b.y - y * b.x + z * b.w,
    )

    fun conjugate() = Quat(w, -x, -y, -z)

    fun normalized(): Quat {
        val n = sqrt(w * w + x * x + y * y + z * z)
        return if (n < 1e-12) IDENTITY else Quat(w / n, x / n, y / n, z / n)
    }

    fun rotate(v: Vec3): Vec3 {
        val r = this * Quat(0.0, v.x, v.y, v.z) * conjugate()
        return Vec3(r.x, r.y, r.z)
    }

    /** 회전 벡터(축 × 각도, 라디안). */
    fun toRotationVector(): Vec3 {
        var q = normalized()
        if (q.w < 0) q = Quat(-q.w, -q.x, -q.y, -q.z)
        val s = sqrt(q.x * q.x + q.y * q.y + q.z * q.z)
        if (s < 1e-9) return Vec3(q.x * 2, q.y * 2, q.z * 2)
        val angle = 2 * atan2(s, q.w)
        return Vec3(q.x / s * angle, q.y / s * angle, q.z / s * angle)
    }

    companion object {
        val IDENTITY = Quat(1.0, 0.0, 0.0, 0.0)

        fun fromAxisAngle(axis: Vec3, radians: Double): Quat {
            val n = axis.length
            val s = kotlin.math.sin(radians / 2) / n
            return Quat(kotlin.math.cos(radians / 2), axis.x * s, axis.y * s, axis.z * s)
        }
    }
}

/**
 * 감도 곡선. 느리게 움직이면 1°당 픽셀이 작아 정밀하고, 빠르게 휘두르면 커서가 멀리 간다.
 * [sensitivity]는 사용자가 앱에서 조절하는 배율이다.
 */
data class PointerSettings(
    val slowGainPxPerDeg: Double = 12.0,
    val fastGainPxPerDeg: Double = 40.0,
    val slowSpeedDegPerSec: Double = 20.0,
    val fastSpeedDegPerSec: Double = 200.0,
    val tighteningDegPerSec: Double = 3.0,
    val sensitivity: Double = 1.0,
)

data class PointerDelta(val dx: Int, val dy: Int, val speedDegPerSec: Double) {
    companion object {
        val ZERO = PointerDelta(0, 0, 0.0)
    }
}

/**
 * 폰 자세 변화 → 커서 이동량.
 *
 * 좌우는 세계 수직축(중력 반대 방향) 둘레 회전이라 폰을 어떤 각도로 쥐든 같은 느낌이다.
 * 위아래는 폰 X축(세로로 쥐었을 때 오른쪽)의 수평 성분 둘레 회전이라,
 * 폰을 눕혀서 위쪽 끝으로 가리키든 세워서 뒷면 카메라로 가리키든 "들어 올리면 위"가 된다.
 */
class PointerMapper(var settings: PointerSettings = PointerSettings()) {
    private var previous: Quat? = null
    private var previousTimeNs = 0L
    private var carryX = 0.0
    private var carryY = 0.0

    /** 이동을 새로 시작할 때 호출한다. 다음 샘플이 기준이 되어 커서가 튀지 않는다. */
    fun reset() {
        previous = null
        carryX = 0.0
        carryY = 0.0
    }

    fun update(orientation: Quat, timestampNs: Long): PointerDelta {
        val prev = previous
        val dt = (timestampNs - previousTimeNs) / 1e9
        previous = orientation
        previousTimeNs = timestampNs
        if (prev == null) return PointerDelta.ZERO
        if (dt <= 0 || dt > MAX_GAP_SECONDS) {
            carryX = 0.0
            carryY = 0.0
            return PointerDelta.ZERO
        }

        val (yawDeg, pitchDeg) = project(prev, orientation)
        val speed = hypot(yawDeg, pitchDeg) / dt
        val s = settings
        val tightening = if (s.tighteningDegPerSec > 0 && speed < s.tighteningDegPerSec) speed / s.tighteningDegPerSec else 1.0
        val t = ((speed - s.slowSpeedDegPerSec) / (s.fastSpeedDegPerSec - s.slowSpeedDegPerSec)).coerceIn(0.0, 1.0)
        val smooth = t * t * (3 - 2 * t)
        val gain = s.slowGainPxPerDeg + (s.fastGainPxPerDeg - s.slowGainPxPerDeg) * smooth
        val scale = gain * tightening * s.sensitivity

        carryX += yawDeg * scale
        carryY -= pitchDeg * scale // 들어 올리면 화면 y는 감소
        val dx = carryX.toInt()
        val dy = carryY.toInt()
        carryX -= dx
        carryY -= dy
        return PointerDelta(dx, dy, speed)
    }

    companion object {
        const val MAX_GAP_SECONDS = 0.25
        private val DEVICE_X = Vec3(1.0, 0.0, 0.0)

        /** 이전 → 현재 자세 사이의 (좌우 회전°, 위아래 기울기°). 오른쪽·위가 양수. */
        fun project(previous: Quat, current: Quat): Pair<Double, Double> {
            val world = (current * previous.conjugate()).toRotationVector()
            val yaw = -world.z // 위에서 볼 때 시계 방향(오른쪽으로 돌림)은 +Z 기준 음의 회전

            val deviceX = current.rotate(DEVICE_X)
            val horizontal = Vec3(deviceX.x, deviceX.y, 0.0)
            val h = horizontal.length
            // 폰을 옆으로 90° 눕혀 X축이 거의 수직이면 위아래 축을 정할 수 없다.
            val pitch = if (h < 0.3) 0.0 else world.dot(horizontal * (1 / h))
            return Math.toDegrees(yaw) to Math.toDegrees(pitch)
        }
    }
}
