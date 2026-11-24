package com.jeiel85.motionpointer

import kotlin.math.abs

/** PC가 주 모니터에 띄우는 마커의 위치. 양쪽에서 같은 값을 써야 한다. */
object MarkerLayout {
    const val INSET = 0.06
    const val SIZE = 0.05

    /** 마커 중심이 가리키는 정규화 화면 좌표. 순서는 좌상·우상·우하·좌하. */
    fun destinations(): List<Point2> = listOf(
        Point2(INSET, INSET),
        Point2(1 - INSET, INSET),
        Point2(1 - INSET, 1 - INSET),
        Point2(INSET, 1 - INSET),
    )
}

/**
 * 카메라 Y 평면에서 밝은 네모 마커 4개를 찾는다.
 *
 * 조명이 좋은 실내에서 흰 마커를 안정적으로 잡기 위한 단순 임계값 + 연결 요소 방식이다.
 * 화면 자체가 밝아서 생기는 가짜 후보를 걸러내려고 네 개가 직사각형을 이루어야 성공으로 본다.
 */
class MarkerDetector {
    fun detect(luma: ByteArray, width: Int, height: Int, rowStride: Int): List<Point2>? {
        if (width <= 0 || height <= 0) return null
        val step = 2
        val sampleWidth = width / step
        val sampleHeight = height / step
        if (sampleWidth < 8 || sampleHeight < 8) return null

        val visited = BooleanArray(sampleWidth * sampleHeight)
        val stack = IntArray(sampleWidth * sampleHeight)
        val candidates = mutableListOf<Marker>()

        for (index in 0 until visited.size) {
            if (visited[index]) continue
            val x = index % sampleWidth
            val y = index / sampleWidth
            val offset = (y * step) * rowStride + x * step
            if (offset >= luma.size || (luma[offset].toInt() and 0xFF) < BRIGHTNESS_THRESHOLD) {
                visited[index] = true
                continue
            }

            var top = 0
            var count = 0
            var sumX = 0L
            var sumY = 0L
            var minX = x
            var maxX = x
            var minY = y
            var maxY = y
            stack[top++] = index
            visited[index] = true
            while (top > 0) {
                val current = stack[--top]
                val cx = current % sampleWidth
                val cy = current / sampleWidth
                count++
                sumX += cx
                sumY += cy
                if (cx < minX) minX = cx
                if (cx > maxX) maxX = cx
                if (cy < minY) minY = cy
                if (cy > maxY) maxY = cy

                val neighbours = intArrayOf(
                    if (cx > 0) current - 1 else -1,
                    if (cx < sampleWidth - 1) current + 1 else -1,
                    if (cy > 0) current - sampleWidth else -1,
                    if (cy < sampleHeight - 1) current + sampleWidth else -1,
                )
                for (neighbour in neighbours) {
                    if (neighbour < 0 || visited[neighbour]) continue
                    val nx = neighbour % sampleWidth
                    val ny = neighbour / sampleWidth
                    val pixel = (ny * step) * rowStride + nx * step
                    if (pixel < luma.size && (luma[pixel].toInt() and 0xFF) >= BRIGHTNESS_THRESHOLD) {
                        visited[neighbour] = true
                        stack[top++] = neighbour
                    }
                }
            }

            val boxWidth = (maxX - minX + 1).toDouble()
            val boxHeight = (maxY - minY + 1).toDouble()
            val aspect = boxWidth / boxHeight
            if (count in MIN_AREA..MAX_AREA && aspect in 0.6..1.6) {
                candidates += Marker(
                    center = Point2(sumX.toDouble() / count * step, sumY.toDouble() / count * step),
                    area = count,
                )
            }
        }

        if (candidates.size != 4) return null
        val largest = candidates.sortedByDescending { it.area }
        if (largest[0].area > largest[3].area * 4) return null // 크기가 제각각이면 마커가 아니다

        val byY = largest.sortedBy { it.center.y }
        val topRow = byY.take(2).sortedBy { it.center.x }
        val bottomRow = byY.drop(2).sortedBy { it.center.x }
        val left = (topRow[0].center.x + bottomRow[0].center.x) / 2
        val right = (topRow[1].center.x + bottomRow[1].center.x) / 2
        val upper = (topRow[0].center.y + topRow[1].center.y) / 2
        val lower = (bottomRow[0].center.y + bottomRow[1].center.y) / 2
        if (right - left < width * 0.3 || lower - upper < height * 0.3) return null
        if (abs(topRow[0].center.y - topRow[1].center.y) > height * 0.12) return null
        if (abs(bottomRow[0].center.y - bottomRow[1].center.y) > height * 0.12) return null

        return listOf(topRow[0].center, topRow[1].center, bottomRow[1].center, bottomRow[0].center)
    }

    private data class Marker(val center: Point2, val area: Int)

    companion object {
        private const val BRIGHTNESS_THRESHOLD = 200
        private const val MIN_AREA = 12
        private const val MAX_AREA = 4000
    }
}
