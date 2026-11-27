package io.github.kkambayashi.michitsumi.core.physics

import io.github.joaomcl.boks2d.collision.Geometry
import io.github.joaomcl.boks2d.core.Body
import io.github.joaomcl.boks2d.core.BodyDef
import io.github.joaomcl.boks2d.core.BodyType
import io.github.joaomcl.boks2d.core.World
import io.github.joaomcl.boks2d.math.Rot
import io.github.joaomcl.boks2d.math.Vec2
import io.github.joaomcl.boks2d.shapes.ShapeDef
import io.github.kkambayashi.michitsumi.core.model.Part

/**
 * Part → Box2D のボディ。
 *
 * 凹型（L・U・輪）を 1 つの凸多角形にはせず、PartGeometry の凸多角形（線分ごとの長方形＋曲がり角の継ぎ目）を
 * 1 ボディの複数の形として合成する。凹分解が不要で、輪の内側の穴も残る。
 * Box2D の多角形は頂点 8 個までだが、長方形（4 個）と継ぎ目（5 個まで）なので収まる
 */
object PartBodyFactory {

    /**
     * @property restitution 反発。0（跳ね返らない）
     * @property angularDamping 回転の減衰。着地したときに角から当たって、回りながら跳ねるのを抑える（#110。決定 63）。
     *   反発はもとから 0 で、跳ねて見えていたのは、角から当たったときの回転だった。0.3 から 3.0 にすると、見本のパーツを台と塔に
     *   いろいろな向きで落としたときの、着いてからの跳ね上がりが平均 3.6 cm → 2.4 cm、いちばん大きいときで 16 cm → 9 cm になった。
     *   4.0 にするともっと小さくなるが、塔から倒れるときの回り方が重く見えるので、3.0 にした。
     *   すべりやすさ（摩擦。歩いた日の天気）と、まっすぐ落ちる速さには効かない
     */
    data class Material(
        val density: Double = 1.0,
        val friction: Double = 0.7,
        val restitution: Double = 0.0,
        val angularDamping: Double = 3.0,
        val linearDamping: Double = 0.05,
    )

    val DEFAULT_MATERIAL = Material()

    /**
     * [world] に、[part] の形のボディを ([x], [y])・向き [angle] [rad] で作る。
     *
     * @param scale パーツの長辺 [m]
     */
    internal fun create(
        world: World,
        part: Part,
        x: Double,
        y: Double,
        angle: Double,
        scale: Double = 1.0,
        material: Material = DEFAULT_MATERIAL,
    ): Body {
        val body = world.createBody(
            BodyDef(
                type = BodyType.Dynamic,
                position = Vec2(x.toFloat(), y.toFloat()),
                rotation = Rot.fromAngle(angle.toFloat()),
                angularDamping = material.angularDamping.toFloat(),
                linearDamping = material.linearDamping.toFloat(),
            ),
        )
        val def = ShapeDef(
            density = material.density.toFloat(),
            friction = material.friction.toFloat(),
            restitution = material.restitution.toFloat(),
        )
        // 当たり判定は角ばった形（見た目は丸い。PartGeometry）
        for (poly in PartGeometry.collisionShapes(part, scale)) {
            val hull = Geometry.computeHull(poly.points.map { Vec2(it.x.toFloat(), it.y.toFloat()) })
            // Box2D は、点が近すぎて潰れた形を多角形にできない（細い継ぎ目など）。そういう形は前後の長方形で足りている
            if (hull.points.size < 3) continue
            body.createPolygonShape(def, Geometry.makePolygon(hull))
        }
        return body
    }
}
