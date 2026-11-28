package io.github.kkambayashi.michitsumi.core

import io.github.kkambayashi.michitsumi.core.model.Part
import io.github.kkambayashi.michitsumi.core.model.Pt
import io.github.kkambayashi.michitsumi.core.physics.StackWorld
import org.junit.Test
import kotlin.math.abs

// 一時的（コミットしない）：立てた棒が倒れるのを、設定で止められるか
class Boks2dSym2Test {
    data class Cfg(val name: String, val sub: Int = 4, val hz: Double = 30.0, val zeta: Double = 10.0, val push: Double = 3.0, val cont: Boolean = true, val sleep: Boolean = true)

    @Test fun sym() {
        val vertical = Part(id = "vertical", points = listOf(Pt(0f, -0.5f), Pt(0f, 0.5f)))
        val cfgs = listOf(
            Cfg("default"), Cfg("continuous off", cont = false), Cfg("sleep off", sleep = false),
            Cfg("hz60", hz = 60.0), Cfg("hz15", hz = 15.0), Cfg("zeta1", zeta = 1.0), Cfg("zeta20", zeta = 20.0),
            Cfg("push0.3", push = 0.3), Cfg("push10", push = 10.0), Cfg("sub2", sub = 2), Cfg("sub1", sub = 1), Cfg("sub16", sub = 16), Cfg("sub32", sub = 32),
        )
        for (c in cfgs) for (dropY in listOf(0.3, 0.9, 1.5)) {
            StackWorld().use { w ->
                w.subStepsForTest = c.sub
                w.contactTuningForTest(c.hz, c.zeta, c.push)
                w.continuousForTest(c.cont)
                w.sleepingForTest(c.sleep)
                val piece = w.place(vertical, 0.0, dropY + 0.6, 0.0)
                var maxW = 0.0; var t = 0.0; var settleAt = -1.0; var lost = false
                // 着地の直後の回る速さ
                var firstW = Double.NaN
                while (t < 6.0) {
                    w.step(1.0 / 60); t += 1.0 / 60
                    if (w.removeFallen().isNotEmpty()) { lost = true; break }
                    val ww = abs(w.angularVelocityForTest(piece))
                    if (firstW.isNaN() && piece.velocityY > -0.5 && t > 0.1) firstW = ww
                    maxW = maxOf(maxW, ww)
                    if (settleAt < 0 && t > 0.5 && w.isSettled()) settleAt = t
                }
                println("SYM2 %-15s 高さ=%.1f 着地直後=%.3f 最大=%.3f rad/s 止まった=%.2fs 落ちた=%s".format(c.name, dropY, firstW, maxW, settleAt, lost))
            }
        }
    }
}
