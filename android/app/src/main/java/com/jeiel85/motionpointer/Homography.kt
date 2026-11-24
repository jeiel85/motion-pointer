package com.jeiel85.motionpointer

import kotlin.math.abs
import kotlin.math.sqrt

data class Point2(val x: Double, val y: Double)

/**
 * 카메라 영상 좌표 → 정규화된 모니터 좌표(0..1)를 잇는 사점 투영 변환.
 *
 * 모니터의 실제 크기나 카메라의 3D 자세가 필요 없다. 대응하는 네 점만 있으면 된다.
 * 수치 안정성을 위해 입력·출력을 평균 0, 평균 거리 sqrt(2)로 정규화한 뒤 8x8 선형식을 풀고 원래 좌표계로 되돌린다.
 */
class Homography private constructor(private val coefficients: DoubleArray) {
    /** 투명이 0에 가까워지면 뒷쪽 점을 투영할 수 없으므로 null. */
    fun map(x: Double, y: Double): Point2? {
        val w = coefficients[6] * x + coefficients[7] * y + coefficients[8]
        if (abs(w) < 1e-12) return null
        return Point2(
            (coefficients[0] * x + coefficients[1] * y + coefficients[2]) / w,
            (coefficients[3] * x + coefficients[4] * y + coefficients[5]) / w,
        )
    }

    companion object {
        private const val TOLERANCE = 1e-3

        /**
         * [source]의 네 점을 [destination]의 네 점으로 보내는 변환을 만든다.
         * 점이 겹치거나 사점이 한 직선에 몰려 있으면 null(추적 실패로 취급).
         */
        fun fromCorrespondences(source: List<Point2>, destination: List<Point2>): Homography? {
            if (source.size != 4 || destination.size != 4) return null

            val sourceTransform = normalization(source) ?: return null
            val destinationTransform = normalization(destination) ?: return null
            val normalizedSource = source.map { sourceTransform.apply(it) }
            val normalizedDestination = destination.map { destinationTransform.apply(it) }

            val matrix = Array(8) { DoubleArray(9) }
            for (i in 0..3) {
                val (x, y) = normalizedSource[i]
                val (u, v) = normalizedDestination[i]
                matrix[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
                matrix[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
            }

            val solved = solve(matrix) ?: return null
            val normalized = DoubleArray(9) { if (it < 8) solved[it] else 1.0 }

            // H = inv(Tdst) * Hn * Tsrc
            val inverseDestination = destinationTransform.inverse()
            val result = multiply(multiply(inverseDestination, normalized), sourceTransform.values)

            val homography = Homography(result)
            val fits = source.indices.all { i ->
                val mapped = homography.map(source[i].x, source[i].y) ?: return@all false
                abs(mapped.x - destination[i].x) < TOLERANCE && abs(mapped.y - destination[i].y) < TOLERANCE
            }
            return if (fits) homography else null
        }

        private class Transform(val values: DoubleArray) {
            fun apply(point: Point2) = Point2(
                values[0] * point.x + values[1] * point.y + values[2],
                values[3] * point.x + values[4] * point.y + values[5],
            )

            fun inverse(): DoubleArray {
                val s = 1.0 / values[0]
                return doubleArrayOf(s, 0.0, -values[2] * s, 0.0, s, -values[5] * s, 0.0, 0.0, 1.0)
            }
        }

        private fun normalization(points: List<Point2>): Transform? {
            val cx = points.sumOf { it.x } / points.size
            val cy = points.sumOf { it.y } / points.size
            val meanDistance = points.sumOf { sqrt((it.x - cx) * (it.x - cx) + (it.y - cy) * (it.y - cy)) } / points.size
            if (meanDistance < 1e-9) return null
            val scale = sqrt(2.0) / meanDistance
            return Transform(doubleArrayOf(scale, 0.0, -scale * cx, 0.0, scale, -scale * cy, 0.0, 0.0, 1.0))
        }

        private fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray {
            val result = DoubleArray(9)
            for (row in 0..2) {
                for (column in 0..2) {
                    for (k in 0..2) result[row * 3 + column] += a[row * 3 + k] * b[k * 3 + column]
                }
            }
            return result
        }

        /** 부분 피벗 가우스 소거. [rows]의 마지막 열이 상수항. */
        private fun solve(rows: Array<DoubleArray>): DoubleArray? {
            val size = rows.size
            for (column in 0 until size) {
                var pivot = column
                for (row in column + 1 until size) {
                    if (abs(rows[row][column]) > abs(rows[pivot][column])) pivot = row
                }
                if (abs(rows[pivot][column]) < 1e-10) return null
                val temporary = rows[column]
                rows[column] = rows[pivot]
                rows[pivot] = temporary

                for (row in column + 1 until size) {
                    val factor = rows[row][column] / rows[column][column]
                    if (factor == 0.0) continue
                    for (k in column..size) rows[row][k] -= factor * rows[column][k]
                }
            }

            val result = DoubleArray(size)
            for (row in size - 1 downTo 0) {
                var value = rows[row][size]
                for (column in row + 1 until size) value -= rows[row][column] * result[column]
                result[row] = value / rows[row][row]
            }
            return result
        }
    }
}
