package io.github.kkambayashi.michitsumi.data

/**
 * パーツの出どころ。記録を始めたときに決まり（[io.github.kkambayashi.michitsumi.collect.TrackRecorder.start]）、
 * パーツができたら、場所や天気と同じく id ごとの別の表に残す（[PartRepository.origins]。スマホの中だけ）。
 *
 * 完成カードの「使った道」は、[WALK] のパーツだけを数える（[walkDistance]）。
 * パーツの id や共通のデータ形式（Part）には入れない（サーバに送るタワーに出どころが見えず、id を作り直しても消えない）
 */
enum class PartOrigin {
    /** 「記録開始」で、本当に歩いて記録した道 */
    WALK,

    /** 「デモ散歩」で流した見本の軌跡 */
    DEMO,

    /** 「ファイル」で流した GPX / JSON */
    FILE,
}
