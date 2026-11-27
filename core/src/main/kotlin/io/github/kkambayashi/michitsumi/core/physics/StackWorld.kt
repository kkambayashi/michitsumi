package io.github.kkambayashi.michitsumi.core.physics

import io.github.kkambayashi.michitsumi.core.model.Part
import io.github.kkambayashi.michitsumi.core.model.Placement
import org.dyn4j.collision.narrowphase.Gjk
import org.dyn4j.dynamics.Body
import org.dyn4j.geometry.Circle
import org.dyn4j.geometry.Geometry
import org.dyn4j.geometry.MassType
import org.dyn4j.geometry.Polygon
import org.dyn4j.geometry.Transform
import org.dyn4j.geometry.Vector2
import org.dyn4j.world.ContactCollisionData
import org.dyn4j.world.ValueMixer
import org.dyn4j.world.World
import org.dyn4j.world.listener.ContactListenerAdapter
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger
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
 * 物理エンジン（dyn4j）の型は、このクラスの外に出さない。置いたパーツは [Piece] で受け渡す
 * （iOS 版で物理エンジンを替えても、画面・チャレンジ・テストを直さずに済むように）
 */
class StackWorld(
    val platformWidth: Double = PLATFORM_WIDTH,
    val platformThickness: Double = PLATFORM_THICKNESS,
    /** これより下に落ちたパーツは「落下」扱い */
    val fallThresholdY: Double = -1.5,
) : AutoCloseable {
    /** 物理エンジン（dyn4j）のワールド。外からは触らせない（[Piece] とテスト用の関数を通す） */
    private val world: World<Body> = World()

    private val platform: Body
    private val placed = LinkedHashMap<Body, Part>()

    /**
     * 置いたパーツの見た目の形（丸い。パーツのローカル座標）。当たり判定（角ばった fixture）とは別に、置いたときに一度だけ作る。
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
            val box = body.createAABB()
            PlacedMark(grips[body] ?: Grip.NORMAL, (box.minX + box.maxX) / 2, box.maxY)
        }
    }

    init {
        val s = world.settings
        s.stepFrequency = 1.0 / 60.0
        s.isAtRestDetectionEnabled = true
        s.maximumAtRestLinearVelocity = 0.05
        s.maximumAtRestAngularVelocity = 0.1
        s.minimumAtRestTime = 0.8
        world.setGravity(0.0, -9.8)
        world.valueMixer = GripMixer
        world.addContactListener(PlatformGrip)

        platform = Body().apply {
            val rect = Geometry.createRectangle(platformWidth, platformThickness)
            rect.translate(0.0, -platformThickness / 2.0)
            val f = addFixture(rect)
            f.friction = PLATFORM_FRICTION
            f.restitution = 0.0
            setMass(MassType.INFINITE)
            userData = PLATFORM_TAG
        }
        world.addBody(platform)
    }

    /** 閉じたか（[close]） */
    var isClosed: Boolean = false
        private set

    /**
     * 使い終わったワールドを閉じる。閉じたあとは使えない（使うと IllegalStateException）。2 回目からは何もしない。
     *
     * 今の物理エンジン（dyn4j）は Java で書かれていて、使わなくなればガベージコレクタが片付けるので、閉じなくても漏れない。
     * ただ、iOS 版のために考えている Box2D（Boks2d）は C のメモリを使うので、閉じないと漏れる。同時に作れるワールドも
     * 128 個までで、作る・消すはスレッドに対して安全でない（ガベージコレクタに任せて別のスレッドで消すことはできない）。
     * エンジンを替えても使う側を直さずに済むよう、今のうちから、作った側が使い終わった時点で閉じる
     */
    override fun close() {
        if (isClosed) return
        try {
            clear()
            world.removeAllBodies()
        } finally {
            // 片付けの途中で例外が出ても、閉じたことにする（数え直しや、閉じたあとの使用の検出がずれないように）
            isClosed = true
            openNow.decrementAndGet()
            synchronized(live) { live.remove(this) }
        }
    }

    /** 閉じたワールドを使おうとしたら止める（エンジンを替えたあとは、閉じたワールドに触ると落ちるので、今のうちに見つける） */
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
        val body = PartBodyFactory.create(part, scale, PartBodyFactory.DEFAULT_MATERIAL.copy(friction = grip.friction))
        body.rotate(angle)
        body.translate(x, y)
        world.addBody(body)
        placed[body] = part
        visuals[body] = PartGeometry.shapes(part, scale)
        bodyIds[body] = nextBodyId++
        scales[body] = scale
        grips[body] = grip
        return Piece(body, this)
    }

    /**
     * 置いたパーツ（[place] が返したもの）を、その場で固める。重さを無限にして、台と同じく、ぶつかっても動かず落ちもしない。
     * 毎日 1 つずつ積むで、昨日までの塔を固めるのに使う（決定 70。今日のパーツが当たっても崩れないように）。
     * 固めたパーツも、塔の高さ・保存する配置・描く形には入る
     */
    fun freeze(piece: Piece) {
        checkOpen()
        val body = piece.body
        require(body in placed) { "置いたパーツだけ固められる" }
        body.setMass(MassType.INFINITE)
        body.linearVelocity.zero()
        body.angularVelocity = 0.0
        body.setAtRest(true)
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
        val transform = Transform().apply {
            rotate(angle)
            translate(x, y)
        }
        val shapes = PartGeometry.collisionShapes(part, scale).map { poly ->
            Geometry.createPolygon(*poly.points.map { Vector2(it.x, it.y) }.toTypedArray())
        }
        for (body in listOf(platform) + placed.keys) {
            for (fixture in body.fixtures) {
                for (shape in shapes) if (gjk.detect(shape, transform, fixture.shape, body.transform)) return true
            }
        }
        return false
    }

    private val gjk = Gjk()

    /** 経過時間ぶん進める。内部は固定ステップ（1/60 s）。 */
    fun step(elapsedSeconds: Double) {
        checkOpen()
        // 一度に進めすぎない（アプリ復帰時など）
        world.update(elapsedSeconds.coerceIn(0.0, 0.1), 5)
    }

    /** 全パーツが静止しているか（固めたパーツ（[freeze]）は動かないので見ない）。 */
    fun isSettled(): Boolean {
        checkOpen()
        return placed.keys.all { it in frozen || it.isAtRest }
    }

    /** まだ動いているパーツの数（固めたパーツは数えない）。 */
    fun movingCount(): Int {
        checkOpen()
        return placed.keys.count { it !in frozen && !it.isAtRest }
    }

    /** 台から落ちたパーツをワールドから外して返す。 */
    fun removeFallen(): List<Part> {
        checkOpen()
        val fallen = placed.keys.filter { it.worldCenter.y < fallThresholdY }
        val parts = fallen.map { placed.remove(it)!! }
        fallen.forEach {
            visuals.remove(it)
            bodyIds.remove(it)
            scales.remove(it)
            grips.remove(it)
            frozen.remove(it)
        }
        fallen.forEach { world.removeBody(it) }
        return parts
    }

    /** 台の上面から、残っているパーツの最高点までの高さ [m]。 */
    fun height(): Double {
        checkOpen()
        var maxY = 0.0
        for (b in placed.keys) {
            val aabb = b.createAABB()
            if (aabb.maxY > maxY) maxY = aabb.maxY
        }
        return maxY
    }

    /** 保存用の最終配置。 */
    fun placements(): List<Placement> {
        checkOpen()
        return placed.map { (b, p) ->
            val t = b.transform
            Placement(
                partId = p.id,
                x = t.translationX.toFloat(),
                y = t.translationY.toFloat(),
                angleRad = t.rotationAngle.toFloat(),
                scale = (scales[b] ?: 1.0).toFloat(),
            )
        }
    }

    fun clear() {
        checkOpen()
        placed.keys.forEach { world.removeBody(it) }
        placed.clear()
        visuals.clear()
        bodyIds.clear()
        scales.clear()
        grips.clear()
        frozen.clear()
    }

    /**
     * 置いたパーツ 1 つ（[place] が返す）の持ち手。物理エンジンのボディは中に隠し、使う側には位置・速さなどだけを見せる。
     * iOS 版で物理エンジンを替えるときに、StackWorld の外を直さずに済むようにするため
     */
    class Piece internal constructor(internal val body: Body, private val owner: StackWorld) {
        /** 重心の位置 [m] */
        val centerX: Double get() { owner.checkOpen(); return body.worldCenter.x }
        val centerY: Double get() { owner.checkOpen(); return body.worldCenter.y }

        /** 速さ [m/s] */
        val velocityX: Double get() { owner.checkOpen(); return body.linearVelocity.x }
        val velocityY: Double get() { owner.checkOpen(); return body.linearVelocity.y }

        /** 当たり判定の形（角ばった多角形）の数 */
        val shapeCount: Int get() { owner.checkOpen(); return body.fixtureCount }

        /** 当たり判定の形ごとの、面の摩擦 */
        val frictions: List<Double> get() { owner.checkOpen(); return body.fixtures.map { it.friction } }

        /** この速さ [m/s] で動かす。止まっていたパーツも起こして動かす */
        fun push(vx: Double, vy: Double) {
            owner.checkOpen()
            body.setAtRest(false)
            body.setLinearVelocity(vx, vy)
        }
    }

    // ---- テスト用（物理エンジンの型を出さずに、テストから中を動かす。internal なので :core の外からは使えない） ----

    /** 重さの向きと大きさ [m/s²] を変える（0 にすると、置いた形が浮いたままになる） */
    internal fun setGravityForTest(x: Double, y: Double) {
        world.setGravity(x, y)
    }

    /** 最後に置いたパーツを、減衰なしで回し続ける（いつまでも止まらない塔を作る） */
    internal fun spinLastForTest(angularVelocity: Double) {
        val body = placed.keys.last()
        body.angularDamping = 0.0
        body.angularVelocity = angularVelocity
    }

    /** 最後に置いたパーツを、その場から動かす（塔が崩れた様子を作る） */
    internal fun moveLastForTest(dx: Double, dy: Double) {
        placed.keys.last().translate(dx, dy)
    }

    /** パーツどうしが触れたときの摩擦。このワールドに設定した混ぜ方（[GripMixer]）を通す（設定し忘れたら、テストで分かるように） */
    internal fun mixFrictionForTest(friction1: Double, friction2: Double): Double = world.valueMixer.mixFriction(friction1, friction2)

    /** [piece] がいま触れている接触ごとの摩擦（台との接触は、[PlatformGrip] で置き換えたあとの値） */
    internal fun contactFrictionsForTest(piece: Piece): List<Double> = world.getContacts(piece.body).map { it.friction }

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
     * 台は fixture の形、パーツは見た目の形（丸い。[PartGeometry.shapes]）。当たり判定の角ばった形は描かない
     */
    fun renderShapes(): List<RenderShape> {
        checkOpen()
        val out = ArrayList<RenderShape>()
        appendShapes(platform, null, out)
        for ((b, p) in placed) appendVisual(b, p, out)
        return out
    }

    private fun appendVisual(body: Body, part: Part, out: MutableList<RenderShape>) {
        val t = body.transform
        val v = Vector2()
        for (poly in visuals[body].orEmpty()) {
            val n = poly.points.size
            val xs = DoubleArray(n)
            val ys = DoubleArray(n)
            for (i in 0 until n) {
                v.set(poly.points[i].x, poly.points[i].y)
                t.transform(v)
                xs[i] = v.x
                ys[i] = v.y
            }
            out += RenderShape.Poly(part, xs, ys, bodyIds[body] ?: PLATFORM_BODY_ID)
        }
    }

    private fun appendShapes(body: Body, part: Part?, out: MutableList<RenderShape>) {
        val t = body.transform
        for (f in body.fixtures) {
            when (val shape = f.shape) {
                is Polygon -> {
                    val v = shape.vertices
                    val xs = DoubleArray(v.size)
                    val ys = DoubleArray(v.size)
                    for (i in v.indices) {
                        val w = t.getTransformed(v[i])
                        xs[i] = w.x
                        ys[i] = w.y
                    }
                    out += RenderShape.Poly(part, xs, ys, bodyIds[body] ?: PLATFORM_BODY_ID)
                }
                is Circle -> {
                    val c = t.getTransformed(shape.center)
                    out += RenderShape.Disc(part, c.x, c.y, shape.radius, bodyIds[body] ?: PLATFORM_BODY_ID)
                }
                else -> Unit
            }
        }
    }

    /**
     * パーツどうしが触れたときの摩擦の決め方。滑りやすい面（雨・雪の日のパーツ。[Grip]）があれば、滑りやすい方の値。
     * 雪のパーツを乗せても、雪のパーツの上に乗せても滑る（dyn4j の決まり（掛けて平方根）だと、相手の面に引っ張られて、ほとんど滑らなかった）。
     * 晴れのパーツどうしなどは、今までどおり掛けて平方根。
     *
     * 台との間はここでは決めず、[PlatformGrip] が台の印で見分けて置き換える（摩擦の値だけでは、台かパーツかを確実には見分けられないため）
     */
    private object GripMixer : ValueMixer {
        override fun mixFriction(friction1: Double, friction2: Double): Double =
            if (friction1 < Grip.NORMAL.friction || friction2 < Grip.NORMAL.friction) {
                min(friction1, friction2)
            } else {
                ValueMixer.DEFAULT_MIXER.mixFriction(friction1, friction2)
            }

        override fun mixRestitution(restitution1: Double, restitution2: Double): Double =
            ValueMixer.DEFAULT_MIXER.mixRestitution(restitution1, restitution2)

        override fun mixRestitutionVelocity(restitutionVelocity1: Double, restitutionVelocity2: Double): Double =
            ValueMixer.DEFAULT_MIXER.mixRestitutionVelocity(restitutionVelocity1, restitutionVelocity2)
    }

    /**
     * 台との間の摩擦：パーツの天気によらず、晴れのパーツと同じ値（[PLATFORM_CONTACT_FRICTION]。台は滑らない）。
     * 雪や雨のパーツでも、台に置いたものは滑り落ちない。台の上でも滑ると、5 本とも落ちて送れない回が増えたため
     * （#71 の D のレビュー：雪だけで積むと 5% → 30.5%）。
     *
     * 台は、ボディの印（[PLATFORM_TAG]）で見分ける（#71 の C のレビュー。摩擦の値で見分けると、摩擦の大きいパーツを足したときに台と間違える）。
     * dyn4j は毎コマ、接触の摩擦を [GripMixer] で決めてから [collision] を呼ぶので、ここで台との接触だけを置き換える
     */
    private object PlatformGrip : ContactListenerAdapter<Body>() {
        override fun collision(collision: ContactCollisionData<Body>) {
            val contact = collision.contactConstraint
            if (contact.body1.userData == PLATFORM_TAG || contact.body2.userData == PLATFORM_TAG) {
                // dyn4j は、接触ごとに摩擦を変える方法を非推奨にしたが、代わりが無い（摩擦を混ぜる ValueMixer には
                // 摩擦の値しか渡らず、台かどうかを見分けられない）。dyn4j 6.0.0 でもこのまま効くので、警告だけ抑える
                @Suppress("DEPRECATION")
                contact.friction = PLATFORM_CONTACT_FRICTION
            }
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
         * ガベージコレクタは片付けられる（アプリでメモリを漏らさない。今の dyn4j では、閉じなくても前と同じ動き）
         */
        private val live = WeakHashMap<StackWorld, Unit>()

        /** まだ閉じていないワールドを、全部閉じる。テストの後始末用（アプリでは、作った側が閉じる） */
        fun closeAllForTest() {
            synchronized(live) { live.keys.toList() }.forEach { it.close() }
        }

        const val PLATFORM_TAG = "platform"

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
