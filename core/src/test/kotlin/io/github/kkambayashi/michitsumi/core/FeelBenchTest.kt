package io.github.kkambayashi.michitsumi.core

import io.github.kkambayashi.michitsumi.core.physics.PartSize
import io.github.kkambayashi.michitsumi.core.physics.StackWorld
import io.github.kkambayashi.michitsumi.core.sample.SampleParts
import org.junit.Test
import kotlin.random.Random

// 一時的（コミットしない）：通常モードと同じ決まりで、ランダムに積んで比べる
// 出す高さ = 塔の高さ + 1.5 m、待つのは 0.8〜6 秒、1 つでも落ちたら終わり、8 つまで
class FeelBenchTest {
    private val N = 200
    private fun bench(name: String, policy: String, configure: (StackWorld) -> Unit) {
        val settleTimes = mutableListOf<Double>()
        var timeouts = 0
        var drops = 0
        val placedUntilEnd = mutableListOf<Int>()
        val heights = mutableListOf<Double>()
        var survived = 0
        val parts = SampleParts.all
        for (seed in 0 until N) {
            val rnd = Random(seed)
            StackWorld().use { w ->
                configure(w)
                var placed = 0
                var over = false
                while (placed < 8 && !over) {
                    val part = parts[rnd.nextInt(parts.size)]
                    val x = when (policy) {
                        "random" -> rnd.nextDouble(-0.4, 0.4)
                        "asis" -> if (rnd.nextBoolean()) 0.0 else rnd.nextDouble(-0.15, 0.15)
                        else -> rnd.nextDouble(-0.15, 0.15)
                    }
                    val angle = when (policy) {
                        "random" -> rnd.nextDouble(0.0, 2 * Math.PI)
                        "asis" -> 0.0
                        else -> rnd.nextInt(4) * Math.PI / 2 + rnd.nextDouble(-0.05, 0.05)
                    }
                    w.place(part, x, w.height() + 1.5, angle, scale = PartSize.of(part.distanceM))
                    placed++
                    drops++
                    var t = 0.0
                    var settled = false
                    while (t < 6.0) {
                        w.step(1.0 / 60); t += 1.0 / 60
                        if (w.removeFallen().isNotEmpty()) over = true
                        if (t >= 0.8 && w.isSettled()) { settled = true; break }
                    }
                    if (settled) settleTimes += t else timeouts++
                }
                if (!over) survived++
                placedUntilEnd += placed
                heights += w.height()
            }
        }
        val s = settleTimes.sorted()
        fun pct(p: Double) = s[((s.size - 1) * p).toInt()]
        println("BENCH %-22s drops=%d settle median=%.2f p90=%.2f max=%.2f timeouts(6s)=%d (%.0f%%)  8-part towers=%d/%d  avg placed=%.2f±%.2f  avg height=%.2f".format(
            "$policy $name", drops, pct(0.5), pct(0.9), s.last(), timeouts, 100.0 * timeouts / drops, survived, N, placedUntilEnd.average(), se(placedUntilEnd.map { it.toDouble() }), heights.average()))
    }

    private fun se(xs: List<Double>): Double { val m = xs.average(); return Math.sqrt(xs.sumOf { (it - m) * (it - m) } / (xs.size - 1) / xs.size) }

    @Test fun run() {
        bench("box2d sub4", "random") { it.subStepsForTest = 4 }
        bench("box2d sub8", "random") { it.subStepsForTest = 8 }
        bench("box2d sub4", "careful") { it.subStepsForTest = 4 }
        bench("box2d sub8", "careful") { it.subStepsForTest = 8 }
        bench("box2d sub4", "asis") { it.subStepsForTest = 4 }
        bench("box2d sub8", "asis") { it.subStepsForTest = 8 }
    }
}
