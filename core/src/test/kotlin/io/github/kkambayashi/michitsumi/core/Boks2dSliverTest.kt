package io.github.kkambayashi.michitsumi.core

import io.github.kkambayashi.michitsumi.core.model.Part
import io.github.kkambayashi.michitsumi.core.model.Pt
import io.github.kkambayashi.michitsumi.core.physics.PartGeometry
import io.github.kkambayashi.michitsumi.core.physics.StackWorld
import io.github.kkambayashi.michitsumi.core.sample.SampleParts
import org.junit.Test
import kotlin.math.hypot

// 一時的（コミットしない）：Box2D に渡したあと、当たり判定の形がどれだけ削れるか
class Boks2dSliverTest {
    private fun inside(px: Double, py: Double, poly: List<Pair<Double, Double>>): Boolean =
        poly.indices.all { i ->
            val (ax, ay) = poly[i]; val (bx, by) = poly[(i + 1) % poly.size]
            (bx - ax) * (py - ay) - (by - ay) * (px - ax) >= -1e-9
        }

    private fun distToSeg(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax; val dy = by - ay
        val t = (((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    /** 点から、Box2D の形の和集合までの距離（中なら 0） */
    private fun dist(px: Double, py: Double, polys: List<List<Pair<Double, Double>>>): Double {
        if (polys.any { inside(px, py, it) }) return 0.0
        return polys.minOf { poly -> poly.indices.minOf { i -> val a = poly[i]; val b = poly[(i + 1) % poly.size]; distToSeg(px, py, a.first, a.second, b.first, b.second) } }
    }

    @Test fun cut() {
        val extra = listOf(
            listOf(-0.5f to 0f, 0.5f to 0f, -0.4f to 0.35f),
            listOf(-0.5f to 0f, 0.5f to 0f, -0.5f to 0.001f),
        ).mapIndexed { i, pts -> Part(id = "extra$i", points = pts.map { Pt(it.first, it.second) }) }
        for (p in SampleParts.all + extra) {
            for (scale in listOf(0.7, 1.0, 1.4)) {
                val polys = PartGeometry.collisionShapes(p, scale)
                val b2 = StackWorld().use { w -> w.localPolygonsForTest(w.place(p, 0.0, 3.0, 0.0, scale)) }
                // 元の形の辺の上の点（細かく）から、Box2D の形までの距離のいちばん大きいもの
                var worst = 0.0
                for (poly in polys) {
                    val ps = poly.points
                    for (i in ps.indices) {
                        val a = ps[i]; val b = ps[(i + 1) % ps.size]
                        for (k in 0..20) {
                            val x = a.x + (b.x - a.x) * k / 20.0; val y = a.y + (b.y - a.y) * k / 20.0
                            worst = maxOf(worst, dist(x, y, b2))
                        }
                    }
                }
                val orig = polys.map { poly -> poly.points.map { it.x to it.y } }
                var lookOrig = 0.0
                for (poly in PartGeometry.shapes(p, scale)) {
                    val ps = poly.points
                    for (i in ps.indices) {
                        val a = ps[i]; val b = ps[(i + 1) % ps.size]
                        for (k in 0..20) {
                            val x = a.x + (b.x - a.x) * k / 20.0; val y = a.y + (b.y - a.y) * k / 20.0
                            lookOrig = maxOf(lookOrig, dist(x, y, orig))
                        }
                    }
                }
                var lookWorst = 0.0
                for (poly in PartGeometry.shapes(p, scale)) {
                    val ps = poly.points
                    for (i in ps.indices) {
                        val a = ps[i]; val b = ps[(i + 1) % ps.size]
                        for (k in 0..20) {
                            val x = a.x + (b.x - a.x) * k / 20.0; val y = a.y + (b.y - a.y) * k / 20.0
                            lookWorst = maxOf(lookWorst, dist(x, y, b2))
                        }
                    }
                }
                println("LOOK %-12s scale=%.1f 見た目のはみ出し 元の形=%.1f mm  Box2D=%.1f mm".format(p.id, scale, lookOrig * 1000, lookWorst * 1000))
                println("CUT %-12s scale=%.1f polys=%d box2d=%d  削れた深さの最大=%.1f mm".format(p.id, scale, polys.size, b2.size, worst * 1000))
            }
        }
    }
}
