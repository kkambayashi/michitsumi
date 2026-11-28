package io.github.kkambayashi.michitsumi.core

import io.github.kkambayashi.michitsumi.core.model.Part
import io.github.kkambayashi.michitsumi.core.model.Pt
import io.github.kkambayashi.michitsumi.core.physics.PartGeometry
import io.github.kkambayashi.michitsumi.core.physics.StackWorld
import io.github.kkambayashi.michitsumi.core.sample.SampleParts
import org.junit.Test
import kotlin.math.abs

// 一時的（コミットしない）：まっすぐな形でも、平らに着地すると回り出すか
class Boks2dSymTest {
    @Test fun sym() {
        val vertical = Part(id = "vertical", points = listOf(Pt(0f, -0.5f), Pt(0f, 0.5f)))
        val horizontal = Part(id = "horizontal", points = listOf(Pt(-0.5f, 0f), Pt(0.5f, 0f)))
        val bar = SampleParts.all[0]
        println("SYM sample-bar points=" + bar.points.joinToString { "(%.3f,%.3f)".format(it.x, it.y) })
        println("SYM sample-bar bottom rect=" + PartGeometry.collisionShapes(bar).first().points.joinToString { "(%.4f,%.4f)".format(it.x, it.y) })
        for (p in listOf(vertical, horizontal, bar)) {
            for (sub in listOf(4, 8)) for (dropY in listOf(0.3, 1.5)) {
                StackWorld().use { w ->
                    w.subStepsForTest = sub
                    val piece = w.place(p, 0.0, dropY + 0.6, 0.0)
                    var maxW = 0.0; var t = 0.0; var settleAt = -1.0
                    var lost = false
                    while (t < 6.0) { w.step(1.0 / 60); t += 1.0 / 60; if (w.removeFallen().isNotEmpty()) lost = true; if (lost) break; maxW = maxOf(maxW, abs(w.angularVelocityForTest(piece))); if (settleAt < 0 && t > 0.5 && w.isSettled()) settleAt = t }
                    println("SYM %-10s sub=%d 落とす高さ=%.1f 回る速さの最大=%.4f rad/s 止まった=%.2fs 台から落ちた=%s".format(p.id, sub, dropY, maxW, settleAt, lost))
                }
            }
        }
    }
}
