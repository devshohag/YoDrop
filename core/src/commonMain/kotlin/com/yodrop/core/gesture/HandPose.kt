package com.yodrop.core.gesture

import kotlin.math.*

data class HandPoint(val x: Float, val y: Float, val z: Float)
enum class HandPose { NONE, UNKNOWN, PALM, FIST }

/** MediaPipe world landmarks in metres; independent of handedness and camera rotation. */
object HandPoseClassifier {
    fun classify(points: List<HandPoint>): HandPose {
        if (points.size != 21 || points.any { !it.x.isFinite() || !it.y.isFinite() || !it.z.isFinite() }) return HandPose.NONE
        fun distance(a: HandPoint, b: HandPoint): Double = sqrt(
            (a.x - b.x).toDouble().pow(2) + (a.y - b.y).toDouble().pow(2) + (a.z - b.z).toDouble().pow(2))
        fun angle(a: Int, b: Int, c: Int): Double {
            val u = doubleArrayOf((points[a].x - points[b].x).toDouble(), (points[a].y - points[b].y).toDouble(), (points[a].z - points[b].z).toDouble())
            val v = doubleArrayOf((points[c].x - points[b].x).toDouble(), (points[c].y - points[b].y).toDouble(), (points[c].z - points[b].z).toDouble())
            val norm = sqrt(u.sumOf { it * it } * v.sumOf { it * it })
            if (norm < 1e-10) return Double.NaN
            return acos((u.indices.sumOf { u[it] * v[it] } / norm).coerceIn(-1.0, 1.0)) * 180 / PI
        }
        val scale = maxOf(distance(points[0], points[9]), distance(points[5], points[17]))
        if (scale < 0.005 || scale > 0.5) return HandPose.NONE
        var extended = 0; var curled = 0
        for (mcp in listOf(5, 9, 13, 17)) {
            val pip = mcp + 1; val dip = mcp + 2; val tip = mcp + 3
            val bend = angle(mcp, pip, dip)
            val end = angle(pip, dip, tip)
            if (!bend.isFinite() || !end.isFinite()) return HandPose.UNKNOWN
            val reach = distance(points[tip], points[0]) / distance(points[pip], points[0]).coerceAtLeast(1e-6)
            if (bend >= 155 && end >= 145 && reach >= 1.10) extended++
            if (bend <= 120 && reach <= 1.15) curled++
        }
        val center = HandPoint((points[0].x + points[5].x + points[9].x + points[13].x + points[17].x) / 5,
            (points[0].y + points[5].y + points[9].y + points[13].y + points[17].y) / 5,
            (points[0].z + points[5].z + points[9].z + points[13].z + points[17].z) / 5)
        val thumbOpen = angle(2, 3, 4) >= 145 && distance(points[4], points[5]) / scale >= 0.65
        val thumbTucked = distance(points[4], center) / scale <= 0.90
        return when {
            extended == 4 && thumbOpen -> HandPose.PALM
            curled == 4 && thumbTucked -> HandPose.FIST
            else -> HandPose.UNKNOWN
        }
    }
}
