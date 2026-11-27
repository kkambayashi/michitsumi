package io.github.kkambayashi.michitsumi.core.physics

import io.github.joaomcl.boks2d.collision.Distance
import io.github.joaomcl.boks2d.collision.DistanceInput
import io.github.joaomcl.boks2d.core.Body
import io.github.joaomcl.boks2d.core.BodyDef
import io.github.joaomcl.boks2d.core.BodyType
import io.github.joaomcl.boks2d.core.World
import io.github.joaomcl.boks2d.core.WorldDef
import io.github.joaomcl.boks2d.math.Transform
import io.github.joaomcl.boks2d.math.Vec2
import io.github.joaomcl.boks2d.shapes.Polygon
import io.github.joaomcl.boks2d.shapes.ShapeDef
import io.github.kkambayashi.michitsumi.core.model.Part
import io.github.kkambayashi.michitsumi.core.model.Placement
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 積み上げゲームの物理ワールド。UI フレームワークに依存しない。
 *
 * ワールド座標：単位 [m]、y 上向き。台の上面が y = 0、台の中心が x = 0。
 * パーツの長辺は 1.0 m。
 *
 * 使い方：
 *   val w = StackWorld()
 *   w.place(part, x = 0.0, y = 2.0, angle = 0.0)
 *   毎フレーム w.step(dt) → w.removeFallen()（落ちたパーツは、落ちた時点で取り除く。落ち続けるパーツは止まらないので、
 *   残しておくと isSettled() が true にならない）
 *   w.isSettled() が true になったら w.height()
 *   使い終わったら w.close()（作った側が必ず閉じる。[close]）
 *
 * 物理エンジン（Box2D v3。Boks2d から呼ぶ）の型は、このクラスの外に出さない。置いたパーツは [Piece] で受け渡す
 * （iOS 版でも同じエンジンを使い、画面・チャレンジ・テストを直さずに済むように）
 */
class StackWorld(
    val platformWidth: Double = PLATFORM_WIDTH,
    val platformThickness: Double = PLATFORM_THICKNESS,
    /** これより下に落ちたパーツは「落下」扱い */
    val fallThresholdY: Double = -1.5,
) : AutoCloseable {
    /** 物理エンジン（Box2D）のワールド。外からは触らせない（[Piece] とテスト用の関数を通す）。C のメモリなので [close] で消す */
    private val world: World = World(WorldDef(gravity = Vec2(0f, GRAVITY)))

    private val platform: Body
    private val placed = LinkedHashMap<Body, Part>()

    /**
     * 置いたパーツの見た目の形（丸い。パーツのローカル座標）。当たり判定（角ばった形）とは別に、置いたときに一度だけ作る。
     * 描くときは、これをボディの位置・向きに動かすだけにする（毎フレーム作り直さない）
     */
    private val visuals = HashMap<Body, List<PartGeometry.Polygon>>()

    /**
     * 置いたパーツ 1 つずつの番号（描く図形の [RenderShape.body]）。置いた順に 1 から振り、使い回さない（台は 0）。
     * 前は `System.identityHashCode` を使っていたが、重ならないことが約束されていないので、2 つが 1 つの積み木として描かれることがあった（#129 の C のレビュー）
     */
    private val bodyIds = HashMap<Body, Int>()
    private var nextBodyId = 1

    /** 置いたパーツの大きさ（長辺 [m]）。保存する配置（[placements]）に入れる */
    private val scales = HashMap<Body, Double>()

    /** 置いたパーツのすべりやすさ（天気のボーナスを数えるため。決定 68） */
    private val grips = HashMap<Body, Grip>()

    /** 固めたパーツ（[freeze]）。動かないので、止まったか（[isSettled]）は見ない */
    private val frozen = HashSet<Body>()

    /**
     * 置いたパーツごとの、ゆっくり（[AT_REST_LINEAR_VELOCITY]・[AT_REST_ANGULAR_VELOCITY] 以下）になってからの時間 [s]。
     * [AT_REST_TIME] 以上なら止まったとみなす（[isSettled]）。Box2D の眠り（触れ合うものをまとめて、0.5 秒で眠らせる）とは別に、
     * 前のエンジン（dyn4j）と同じ決まりで数える（止まるまで待つ時間が、エンジンで変わらないように）
     */
    private val restTimes = HashMap<Body, Double>()

    /** まだ進めていない時間 [s]（[step] は 1/60 秒ずつ進め、余りは次に持ち越す） */
    private var pendingTime = 0.0

    /** 置かれているパーツ（落下で消えたものは含まない） */
    val placedParts: List<Part> get() = placed.values.toList()

    /** 置かれているパーツのすべりやすさ（落下で消えたものは含まない。置いた数だけ並ぶ）。天気のボーナス（[WeatherBonus]）を数える */
    val placedGrips: List<Grip> get() = placed.keys.map { grips[it] ?: Grip.NORMAL }

    /**
     * 置いたパーツ 1 つの、すべりやすさと、てっぺんのまん中の位置 [m]（当たり判定の形を囲む長方形の上の辺のまん中）。
     * 終わったときに、天気のボーナスの付いたパーツの上に「雪 +0.20」の札を出す場所（決定 68）
     */
    data class PlacedMark(val grip: Grip, val x: Double, val topY: Double)

    /** 置かれているパーツごとの [PlacedMark]（落下で消えたものは含まない。置いた順） */
    fun placedMarks(): List<PlacedMark> {
        checkOpen()
        return placed.keys.map { body ->
            val box = boundsOf(body)
            PlacedMark(grips[body] ?: Grip.NORMAL, (box.minX + box.maxX) / 2.0, box.maxY)
        }
    }

    init {
        // 摩擦の混ぜ方（パーツどうしと、台との間。[mixFriction]）
        world.setFrictionCallback(::mixFrictionCallback)

        platform = world.createBody(BodyDef(type = BodyType.Static, position = Vec2(0f, (-platformThickness / 2.0).toFloat())))
        val shape = platform.createPolygonShape(
            ShapeDef(friction = PLATFORM_FRICTION.toFloat(), restitution = 0f),
            Polygon.makeBox((platformWidth / 2.0).toFloat(), (platformThickness / 2.0).toFloat()),
        )
        // 台の印。摩擦の混ぜ方で、台との接触を見分ける（摩擦の値では見分けない。[mixFriction]）
        shape.material = PLATFORM_MATERIAL
    }

    /** 閉じたか（[close]） */
    var isClosed: Boolean = false
        private set

    /**
     * 使い終わったワールドを閉じる。閉じたあとは使えない（使うと IllegalStateException）。2 回目からは何もしない。
     *
     * Box2D は C で書かれていて、ワールドは C のメモリにある。ガベージコレクタは片付けないので、閉じて消す。
     * 同時に作れるワールドは 128 個までで、作る・消すはスレッドに対して安全でない（ガベージコレクタに任せて
     * 別のスレッドで消すことはできない）ので、作った側が使い終わった時点で閉じる
     */
    override fun close() {
        if (isClosed) return
        try {
            clear()
        } finally {
            // 片付けの途中で例外が出ても、閉じたことにする（数え直しや、閉じたあとの使用の検出がずれないように）
            isClosed = true
            world.destroy()
            openNow.decrementAndGet()
            synchronized(live) { live.remove(this) }
        }
    }

    /** 閉じたワールドを使おうとしたら止める（閉じたワールドの C のメモリに触ると、アプリが落ちるので） */
    private fun checkOpen() = check(!isClosed) { "閉じた StackWorld は使えない" }

    /**
     * パーツをワールドに追加して落下させる。
     *
     * @param scale パーツの大きさ（長辺 [m]。歩いた距離で決まる。[PartSize.of]）
     * @param grip すべりやすさ（歩いたときの天気で決まる。[Grip.of]）
     * @return 置いたパーツ（[freeze] に渡したり、テストで動きを見たりする）
     */
    fun place(part: Part, x: Double, y: Double, angle: Double, scale: Double = 1.0, grip: Grip = Grip.NORMAL): Piece {
        checkOpen()
        val body = PartBodyFactory.create(
            world, part, x, y, angle, scale,
            PartBodyFactory.DEFAULT_MATERIAL.copy(friction = grip.friction),
        )
        placed[body] = part
        visuals[body] = PartGeometry.shapes(part, scale)
        bodyIds[body] = nextBodyId++
        scales[body] = scale
        grips[body] = grip
        restTimes[body] = 0.0
        return Piece(body, this)
    }

    /**
     * 置いたパーツ（[place] が返したもの）を、その場で固める。動かない物体（台と同じ）にして、ぶつかっても動かず落ちもしない。
     * 毎日 1 つずつ積むで、昨日までの塔を固めるのに使う（決定 70。今日のパーツが当たっても崩れないように）。
     * 固めたパーツも、塔の高さ・保存する配置・描く形には入る
     */
    fun freeze(piece: Piece) {
        checkOpen()
        val body = piece.body
        require(body in placed) { "置いたパーツだけ固められる" }
        body.type = BodyType.Static
        frozen += body
    }

    /**
     * [part] を ([x], [y]) に [angle] [rad] で置いたとしたら、台か、置いてあるパーツに重なるか（当たり判定の角ばった形どうし）。
     * 3 分チャレンジの「自動で落ちる」で、下がってくる形が塔や台に触れたかを見るのに使う（決定 65）。ワールドは変えない
     *
     * @param scale パーツの大きさ（長辺 [m]）
     */
    fun overlaps(part: Part, x: Double, y: Double, angle: Double, scale: Double = 1.0): Boolean {
        checkOpen()
        val c = kotlin.math.cos(angle)
        val s = kotlin.math.sin(angle)
        val candidates = PartGeometry.collisionShapes(part, scale).map { poly ->
            Distance.makeProxy(
                poly.points.map { Vec2((c * it.x - s * it.y + x).toFloat(), (s * it.x + c * it.y + y).toFloat()) },
                0f,
            )
        }
        for (body in listOf(platform) + placed.keys) {
            val t = body.transform
            for (shape in body.getShapes()) {
                val existing = Distance.makeProxy(shape.getPolygon().vertices.map { t.transformPoint(it) }, 0f)
                for (candidate in candidates) {
                    val d = Distance.shapeDistance(DistanceInput(candidate, existing, useRadii = false))
                    if (d.distance <= 0f) return true
                }
            }
        }
        return false
    }

    /**
     * 経過時間ぶん進める。内部は固定ステップ（1/60 s）。1 回に進めるのは [MAX_STEPS] ステップまでで、
     * 余った時間は次に持ち越す（前のエンジン dyn4j の `update(経過時間, 5)` と同じ決まり）
     */
    fun step(elapsedSeconds: Double) {
        checkOpen()
        // 一度に進めすぎない（アプリ復帰時など）
        pendingTime += elapsedSeconds.coerceIn(0.0, 0.1)
        var steps = 0
        while (pendingTime >= STEP_SECONDS && steps < MAX_STEPS) {
            world.step(STEP_SECONDS.toFloat(), SUB_STEPS)
            pendingTime -= STEP_SECONDS
            updateRestTimes()
            steps++
        }
    }

    /** 置いたパーツごとに、ゆっくりになってからの時間を数える（[restTimes]） */
    private fun updateRestTimes() {
        for (body in placed.keys) {
            if (body in frozen) continue
            val v = body.linearVelocity
            val moving = v.x * v.x + v.y * v.y > AT_REST_LINEAR_VELOCITY * AT_REST_LINEAR_VELOCITY ||
                abs(body.angularVelocity) > AT_REST_ANGULAR_VELOCITY
            restTimes[body] = if (moving) 0.0 else (restTimes[body] ?: 0.0) + STEP_SECONDS
        }
    }

    private fun isAtRest(body: Body): Boolean = (restTimes[body] ?: 0.0) >= AT_REST_TIME

    /** 全パーツが静止しているか（固めたパーツ（[freeze]）は動かないので見ない）。 */
    fun isSettled(): Boolean {
        checkOpen()
        return placed.keys.all { it in frozen || isAtRest(it) }
    }

    /** まだ動いているパーツの数（固めたパーツは数えない）。 */
    fun movingCount(): Int {
        checkOpen()
        return placed.keys.count { it !in frozen && !isAtRest(it) }
    }

    /** 台から落ちたパーツをワールドから外して返す。 */
    fun removeFallen(): List<Part> {
        checkOpen()
        val fallen = placed.keys.filter { it.worldCenter.y < fallThresholdY }
        val parts = fallen.map { placed.remove(it)!! }
        fallen.forEach {
            forget(it)
            world.destroyBody(it)
        }
        return parts
    }

    /** 外したボディの覚え書きを消す */
    private fun forget(body: Body) {
        visuals.remove(body)
        bodyIds.remove(body)
        scales.remove(body)
        grips.remove(body)
        frozen.remove(body)
        restTimes.remove(body)
    }

    /** 台の上面から、残っているパーツの最高点までの高さ [m]。 */
    fun height(): Double {
        checkOpen()
        var maxY = 0.0
        for (b in placed.keys) {
            val top = boundsOf(b).maxY
            if (top > maxY) maxY = top
        }
        return maxY
    }

    private class Bounds(val minX: Double, val maxX: Double, val maxY: Double)

    /**
     * ボディの当たり判定の形を囲む長方形（形の頂点から求める）。
     * Box2D がボディに持っている長方形（aabb）は、先読みの接触のために 0.02 m 広げてあるので、高さには使わない
     */
    private fun boundsOf(body: Body): Bounds {
        val t = body.transform
        var minX = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (shape in body.getShapes()) {
            for (v in shape.getPolygon().vertices) {
                val w = t.transformPoint(v)
                minX = minOf(minX, w.x.toDouble())
                maxX = maxOf(maxX, w.x.toDouble())
                maxY = maxOf(maxY, w.y.toDouble())
            }
        }
        return Bounds(minX, maxX, maxY)
    }

    /** 保存用の最終配置。 */
    fun placements(): List<Placement> {
        checkOpen()
        return placed.map { (b, p) ->
            val position = b.position
            Placement(
                partId = p.id,
                x = position.x,
                y = position.y,
                angleRad = b.angle,
                scale = (scales[b] ?: 1.0).toFloat(),
            )
        }
    }

    fun clear() {
        checkOpen()
        placed.keys.forEach { world.destroyBody(it) }
        placed.clear()
        visuals.clear()
        bodyIds.clear()
        scales.clear()
        grips.clear()
        frozen.clear()
        restTimes.clear()
    }

    /**
     * 置いたパーツ 1 つ（[place] が返す）の持ち手。物理エンジンのボディは中に隠し、使う側には位置・速さなどだけを見せる。
     * iOS 版で物理エンジンを替えるときに、StackWorld の外を直さずに済むようにするため
     */
    class Piece internal constructor(internal val body: Body, private val owner: StackWorld) {
        /** 重心の位置 [m] */
        val centerX: Double get() { owner.checkOpen(); return body.worldCenter.x.toDouble() }
        val centerY: Double get() { owner.checkOpen(); return body.worldCenter.y.toDouble() }

        /** 速さ [m/s] */
        val velocityX: Double get() { owner.checkOpen(); return body.linearVelocity.x.toDouble() }
        val velocityY: Double get() { owner.checkOpen(); return body.linearVelocity.y.toDouble() }

        /** 当たり判定の形（角ばった多角形）の数 */
        val shapeCount: Int get() { owner.checkOpen(); return body.shapeCount }

        /** 当たり判定の形ごとの、面の摩擦 */
        val frictions: List<Double> get() { owner.checkOpen(); return body.getShapes().map { it.friction.toDouble() } }

        /** この速さ [m/s] で動かす。止まっていたパーツも起こして動かす */
        fun push(vx: Double, vy: Double) {
            owner.checkOpen()
            body.isAwake = true
            body.linearVelocity = Vec2(vx.toFloat(), vy.toFloat())
        }
    }

    // ---- テスト用（物理エンジンの型を出さずに、テストから中を動かす。internal なので :core の外からは使えない） ----

    /** 置いたパーツの重さ [kg]（形と密度から決まる） */
    internal fun massForTest(piece: Piece): Double = piece.body.mass.toDouble()

    /** 重さの向きと大きさ [m/s²] を変える（0 にすると、置いた形が浮いたままになる） */
    internal fun setGravityForTest(x: Double, y: Double) {
        world.gravity = Vec2(x.toFloat(), y.toFloat())
    }

    /** 最後に置いたパーツを、減衰なしで回し続ける（いつまでも止まらない塔を作る） */
    internal fun spinLastForTest(angularVelocity: Double) {
        val body = placed.keys.last()
        body.angularDamping = 0f
        body.isAwake = true
        body.angularVelocity = angularVelocity.toFloat()
    }

    /** 最後に置いたパーツを、その場から動かす（塔が崩れた様子を作る） */
    internal fun moveLastForTest(dx: Double, dy: Double) {
        val body = placed.keys.last()
        val t = body.transform
        body.transform = Transform(Vec2(t.position.x + dx.toFloat(), t.position.y + dy.toFloat()), t.rotation)
    }

    /**
     * パーツどうしが触れたときの摩擦（[mixFriction]）。
     * Box2D には、ワールドに設定した混ぜ方を読み出す方法が無いので、同じ関数を直接呼ぶ（設定し忘れは、滑り方のテストで分かる）
     */
    internal fun mixFrictionForTest(friction1: Double, friction2: Double): Double = mixFriction(friction1, 0, friction2, 0)

    /**
     * [piece] がいま触れている接触ごとの摩擦（台との接触は、台の値で置き換えたあと）。
     * Box2D の接触は摩擦を見せないので、触れている形の摩擦と印から [mixFriction] で求める
     */
    internal fun contactFrictionsForTest(piece: Piece): List<Double> =
        piece.body.getContactData().map { c ->
            mixFriction(c.shapeA.friction.toDouble(), c.shapeA.material, c.shapeB.friction.toDouble(), c.shapeB.material)
        }

    // ---- 描画用 ----

    /**
     * 描く図形 1 つ。
     * @property body どのボディ（台・置いたパーツ 1 つ）の図形か。同じ値の図形は 1 つの積み木なので、まとめて輪郭を描く（決定 74）。
     *   同じパーツを何度も置いたときも、置いたもの 1 つずつで違う値になる
     */
    sealed class RenderShape {
        abstract val part: Part?
        abstract val body: Int

        /** ワールド座標の多角形頂点（x, y の順に並ぶ） */
        data class Poly(override val part: Part?, val xs: DoubleArray, val ys: DoubleArray, override val body: Int = 0) : RenderShape()
        data class Disc(override val part: Part?, val cx: Double, val cy: Double, val r: Double, override val body: Int = 0) : RenderShape()
    }

    /**
     * 台と全パーツを、ワールド座標の図形にする（毎フレーム描画用）。
     * 台は当たり判定の形、パーツは見た目の形（丸い。[PartGeometry.shapes]）。当たり判定の角ばった形は描かない
     */
    fun renderShapes(): List<RenderShape> {
        checkOpen()
        val out = ArrayList<RenderShape>()
        appendPlatform(out)
        for ((b, p) in placed) appendVisual(b, p, out)
        return out
    }

    private fun appendVisual(body: Body, part: Part, out: MutableList<RenderShape>) {
        val position = body.position
        val rotation = body.rotation
        val c = rotation.cos.toDouble()
        val s = rotation.sin.toDouble()
        val px = position.x.toDouble()
        val py = position.y.toDouble()
        for (poly in visuals[body].orEmpty()) {
            val n = poly.points.size
            val xs = DoubleArray(n)
            val ys = DoubleArray(n)
            for (i in 0 until n) {
                val p = poly.points[i]
                xs[i] = c * p.x - s * p.y + px
                ys[i] = s * p.x + c * p.y + py
            }
            out += RenderShape.Poly(part, xs, ys, bodyIds[body] ?: PLATFORM_BODY_ID)
        }
    }

    private fun appendPlatform(out: MutableList<RenderShape>) {
        val t = platform.transform
        for (shape in platform.getShapes()) {
            val vertices = shape.getPolygon().vertices
            val xs = DoubleArray(vertices.size)
            val ys = DoubleArray(vertices.size)
            for (i in vertices.indices) {
                val w = t.transformPoint(vertices[i])
                xs[i] = w.x.toDouble()
                ys[i] = w.y.toDouble()
            }
            out += RenderShape.Poly(null, xs, ys, PLATFORM_BODY_ID)
        }
    }

    // 数えるのは、作り終わった最後（途中で例外が出たら、作れていないので数えない。プロパティの初期化はこの上で済んでいる）
    init {
        openNow.incrementAndGet()
        synchronized(live) { live[this] = Unit }
    }

    companion object {
        /** 作った数 − 閉じた数。ガベージコレクタに片付けられても減らないので、閉じ忘れはそのまま数に残る */
        private val openNow = AtomicInteger()

        /** まだ閉じていないワールドの数。閉じ忘れを見つけるのに使う（テストで、作り直しても増えないことを確かめる） */
        val openCount: Int get() = openNow.get()

        /**
         * まだ閉じていないワールド（テストの後始末 [closeAllForTest] 用）。弱い参照で持つので、閉じ忘れたワールドも
         * ガベージコレクタは片付けられる（ただし C のメモリは残る。アプリでは、作った側が閉じる）
         */
        private val live = WeakHashMap<StackWorld, Unit>()

        /** まだ閉じていないワールドを、全部閉じる。テストの後始末用（アプリでは、作った側が閉じる） */
        fun closeAllForTest() {
            synchronized(live) { live.keys.toList() }.forEach { it.close() }
        }

        /** 重さ [m/s²] */
        private const val GRAVITY = -9.8f

        /** 1 ステップの時間 [s] と、1 回の [step] で進める最大のステップ数 */
        private const val STEP_SECONDS = 1.0 / 60.0
        private const val MAX_STEPS = 5

        /** 1 ステップの中で、Box2D が細かく分けて解く回数（Box2D の勧める値） */
        private const val SUB_STEPS = 4

        /** これ以下の速さが [AT_REST_TIME] 続いたら、止まったとみなす（前のエンジンと同じ値） */
        private const val AT_REST_LINEAR_VELOCITY = 0.05
        private const val AT_REST_ANGULAR_VELOCITY = 0.1
        private const val AT_REST_TIME = 0.8

        /** 台の形の印（Box2D の material）。パーツは 0 */
        private const val PLATFORM_MATERIAL = 1

        /**
         * 触れ合った 2 つの面の摩擦の決め方。
         *
         * - 台との間：パーツの天気によらず、晴れのパーツと同じ値（[PLATFORM_CONTACT_FRICTION]。台は滑らない）。
         *   雪や雨のパーツでも、台に置いたものは滑り落ちない。台の上でも滑ると、5 本とも落ちて送れない回が増えたため
         *   （#71 の D のレビュー：雪だけで積むと 5% → 30.5%）。台は、形の印（[PLATFORM_MATERIAL]）で見分ける
         *   （#71 の C のレビュー。摩擦の値で見分けると、摩擦の大きいパーツを足したときに台と間違える）
         * - パーツどうし：滑りやすい面（雨・雪の日のパーツ。[Grip]）があれば、滑りやすい方の値。雪のパーツを乗せても、
         *   雪のパーツの上に乗せても滑る（掛けて平方根だと、相手の面に引っ張られて、ほとんど滑らなかった）。
         *   晴れのパーツどうしなどは、掛けて平方根
         */
        private fun mixFriction(friction1: Double, material1: Int, friction2: Double, material2: Int): Double = when {
            material1 == PLATFORM_MATERIAL || material2 == PLATFORM_MATERIAL -> PLATFORM_CONTACT_FRICTION
            friction1 < Grip.NORMAL.friction || friction2 < Grip.NORMAL.friction -> min(friction1, friction2)
            else -> sqrt(friction1 * friction2)
        }

        /** Box2D に渡す形（Float）。中の計算は [mixFriction] */
        private fun mixFrictionCallback(friction1: Float, material1: Int, friction2: Float, material2: Int): Float =
            mixFriction(friction1.toDouble(), material1, friction2.toDouble(), material2).toFloat()

        /** 台の図形の [RenderShape.body]（置いたパーツは 1 から） */
        const val PLATFORM_BODY_ID = 0

        /** 台の面の摩擦 */
        const val PLATFORM_FRICTION = 0.9

        /** 台とパーツの間の摩擦。どの天気のパーツでも、晴れのパーツと台の間と同じ値（掛けて平方根。約 0.79） */
        val PLATFORM_CONTACT_FRICTION: Double = sqrt(Grip.NORMAL.friction * PLATFORM_FRICTION)

        /** 台の幅と厚さ [m]。物理と、共有ページの絵（TowerSvg / TowerPng）と、アプリの絵（TowerSketch）で同じ値を使う */
        const val PLATFORM_WIDTH = 1.4
        const val PLATFORM_THICKNESS = 0.3
    }
}
