package com.jeiel85.motionpointer

import kotlin.math.hypot

/**
 * 카메라가 잡은 절대 위치를 기준으로, 카메라 프레임 사이를 자이로로 메운다.
 *
 * 카메라는 15~30Hz라 움직임이 끊겨 보이므로 100Hz 자이로로 그 사이를 보간한다.
 * 호모그래피는 마지막 카메라 프레임의 자세에 고정되어 있으므로, 영상 속 조준점을
 * 회전량만큼 옮겨 "지금 이 자세라면 화면의 어디를 보고 있는가"를 추정한다.
 */
class AbsolutePointer(
    private val focalFactor: Double = 0.7,
    private val correctionAlpha: Double = 0.35,
) {
    private var homography: Homography? = null
    private var imageWidth = 0.0
    private var aimX = 0.0
    private var aimY = 0.0
    private var screenX = 0.5
    private var screenY = 0.5
    private var valid = false

    /** 새 카메라 프레임에서 호모그래피를 갱신한다. 측정값과 추정값이 크게 어긋나면 즉시 다시 맞춘다. */
    fun onCamera(newHomography: Homography, width: Int, height: Int): Boolean {
        val centerX = width / 2.0
        val centerY = height / 2.0
        val measured = newHomography.map(centerX, centerY)
        if (measured == null) {
            lost()
            return false
        }

        imageWidth = width.toDouble()
        aimX = centerX
        aimY = centerY
        homography = newHomography

        if (!valid || hypot(measured.x - screenX, measured.y - screenY) > RESET_DISTANCE) {
            screenX = measured.x
            screenY = measured.y
        } else {
            screenX += (measured.x - screenX) * correctionAlpha
            screenY += (measured.y - screenY) * correctionAlpha
        }
        clampScreen()
        valid = true
        return true
    }

    /**
     * 자이로가 측정한 자세 변화를 영상 속 조준점 이동으로 바꾼다.
     * [PointerMapper.project]와 같은 부호 규약: 오른쪽으로 돌리면 yaw > 0, 조준점을 들면 pitch > 0.
     */
    fun onGyro(yawDeg: Double, pitchDeg: Double) {
        val h = homography ?: return
        if (!valid || imageWidth <= 0.0) return

        // 오른쪽으로 돌리면 모니터는 영상에서 왼쪽으로 밀리므로, 중심에 있는 조준점은
        // 오른쪽으로 f*yaw만큼 옮겨야 같은 화면 지점이 된다. 위로 들면 반대 방향.
        val focal = imageWidth * focalFactor
        val deltaX = focal * Math.toRadians(yawDeg)
        val deltaY = -focal * Math.toRadians(pitchDeg)

        val right = h.map(aimX + JACOBIAN_STEP, aimY) ?: return
        val left = h.map(aimX - JACOBIAN_STEP, aimY) ?: return
        val down = h.map(aimX, aimY + JACOBIAN_STEP) ?: return
        val up = h.map(aimX, aimY - JACOBIAN_STEP) ?: return
        val divisor = 2 * JACOBIAN_STEP

        screenX += ((right.x - left.x) * deltaX + (down.x - up.x) * deltaY) / divisor
        screenY += ((right.y - left.y) * deltaX + (down.y - up.y) * deltaY) / divisor
        aimX += deltaX
        aimY += deltaY
        clampScreen()
    }

    /** 마지막으로 추정한 정규화 좌표. 추적을 잃으면 null. */
    fun position(): Point2? = if (valid) Point2(screenX, screenY) else null

    fun lost() {
        valid = false
        homography = null
    }

    private fun clampScreen() {
        screenX = screenX.coerceIn(0.0, 1.0)
        screenY = screenY.coerceIn(0.0, 1.0)
    }

    companion object {
        private const val JACOBIAN_STEP = 0.5 // 픽셀
        private const val RESET_DISTANCE = 0.08 // 정규화 좌표. 8% 이상 어긋나면 새 기준으로 받아들인다.
    }
}
