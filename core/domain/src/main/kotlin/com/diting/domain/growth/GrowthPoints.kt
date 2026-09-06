package com.diting.domain.growth

import kotlinx.serialization.Serializable

/**
 * The BP economy — principle 6: **成长值奖励闭环，不奖励录音量**.
 *
 * Every scoring event here is something the user *closed*: confirming a fact,
 * adopting an artefact, correcting a transcript. Nothing pays out for recording
 * more, staying connected longer, or leaving the realtime channel on, because
 * those are exactly the behaviours the cost principle asks users to avoid.
 */
@Serializable
enum class GrowthEvent(val points: Int, val label: String) {
    /** 点「确认」把一个待确认事实写进图谱 */
    CONFIRM_FACT(5, "确认一条事实"),

    /** 点「存疑」也算参与 —— 它同样让图谱更准 */
    DISPUTE_FACT(5, "标记一条存疑"),

    /** 采用 Agent 产出的工件 */
    ADOPT_ARTIFACT(15, "采用一份产出"),

    /** 改一个转写错字（随手教） */
    CORRECT_TRANSCRIPT(3, "纠正一处转写"),

    /** 在观察卡上点「不重要」，回训噪音阈值 */
    TEACH_NOISE_THRESHOLD(3, "教会一次忽略"),

    /** 完成一次周复盘的对表 */
    COMPLETE_WEEKLY_REVIEW(20, "完成一次周对表"),

    /** 完成首次引导 */
    COMPLETE_ONBOARDING(30, "完成首次引导"),
}

/**
 * Level thresholds. Deliberately shallow — the design flags the growth system as
 * being in tension with 谛听's restrained temperament, so it should read as gentle
 * acknowledgement rather than a treadmill.
 */
@Serializable
data class GrowthState(
    val totalPoints: Int = 0,
) {
    val level: Int get() = LEVELS.count { totalPoints >= it }.coerceAtLeast(1)

    /** Points still needed for the next level, or null once maxed out. */
    val pointsToNextLevel: Int?
        get() = LEVELS.firstOrNull { it > totalPoints }?.minus(totalPoints)

    /** 0..1 progress through the current level. */
    val levelProgress: Float
        get() {
            val floor = LEVELS.lastOrNull { totalPoints >= it } ?: 0
            val ceiling = LEVELS.firstOrNull { it > totalPoints } ?: return 1f
            return ((totalPoints - floor).toFloat() / (ceiling - floor)).coerceIn(0f, 1f)
        }

    fun award(event: GrowthEvent): GrowthState = copy(totalPoints = totalPoints + event.points)

    companion object {
        private val LEVELS = listOf(50, 150, 350, 700, 1200, 2000)
    }
}
