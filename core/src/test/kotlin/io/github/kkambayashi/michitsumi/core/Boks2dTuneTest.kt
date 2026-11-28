package io.github.kkambayashi.michitsumi.core

import io.github.kkambayashi.michitsumi.core.physics.StackWorld
import io.github.kkambayashi.michitsumi.core.sample.SampleParts
import org.junit.Test
import kotlin.math.abs

// 一時的（コミットしない）：立てた棒の揺れを調整で止められるかを見る
class Boks2dTuneTest {
    data class Cfg(val name: String, val sub: Int = 4, val hz: Double = 30.0, val zeta: Double = 10.0, val push: Double = 3.0)

    private fun apply(w: StackWorld, c: Cfg) {
        w.subStepsForTest = c.sub
        w.contactTuningForTest(c.hz, c.zeta, c.push)
    }

    @Test fun traceBar() {
        StackWorld().use { w ->
            val bar = SampleParts.all[0]
            val p = w.place(bar, 0.0, 1.0, 0.0)
            var t = 0.0
            while (t < 5.0) {
                w.step(1.0 / 60); t += 1.0 / 60
                val n = Math.round(t * 60)
                if (n % 6 == 0L) println("TRACE t=%.2f c=(%.3f,%.3f) v=(%.3f,%.3f) a=%.4f w=%.4f settled=%s".format(
                    t, p.centerX, p.centerY, p.velocityX, p.velocityY, w.angleForTest(p), w.angularVelocityForTest(p), w.isSettled()))
            }
        }
    }

    @Test fun sweep() {
        val cfgs = listOf(
            Cfg("default"),
            Cfg("sub8", sub = 8),
            Cfg("hz60", hz = 60.0),
            Cfg("hz120", hz = 120.0),
            Cfg("zeta1", zeta = 1.0),
            Cfg("zeta5", zeta = 5.0),
            Cfg("push1", push = 1.0),
            Cfg("push0.5", push = 0.5),
            Cfg("sub8 hz60", sub = 8, hz = 60.0),
            Cfg("sub8 push1", sub = 8, push = 1.0),
            Cfg("hz60 push1", hz = 60.0, push = 1.0),
        )
        for (c in cfgs) {
            // 1. 棒だけ：止まるまでの時間
            val barSettle = StackWorld().use { w ->
                apply(w, c)
                w.place(SampleParts.all[0], 0.0, 1.0, 0.0)
                settle(w)
            }
            // 2. 3 つ積む（落ちたテストと同じ）
            val stack = StackWorld().use { w ->
                apply(w, c)
                var y = 1.0
                val times = mutableListOf<String>()
                for (part in SampleParts.all.take(3)) {
                    w.place(part, 0.0, y, 0.0)
                    times += "%.2f".format(settle(w))
                    w.removeFallen()
                    y = w.height() + 1.0
                }
                "left=${w.placedParts.size} h=%.3f times=$times".format(w.height())
            }
            println("SWEEP %-12s bar=%.2fs  stack: %s".format(c.name, barSettle, stack))
        }
    }

    private fun settle(w: StackWorld, max: Double = 10.0): Double {
        var t = 0.0
        while (t < max) {
            w.step(1.0 / 60); t += 1.0 / 60
            if (t > 1.0 && w.isSettled()) break
        }
        return t
    }
}
