package com.diting.app.ui.nav

/**
 * Every destination in the app.
 *
 * The deck's navigation rule is that all 22 screens are reachable from one of
 * the four tabs — nothing is orphaned behind a deep link. The comment on each
 * route names the entry point that satisfies that, which is what made the
 * "断链" review in the design chat findable in the first place.
 */
object Routes {

    // -- tab roots (4) -------------------------------------------------------

    /** Tab 1. 今日谛听. */
    const val TODAY = "today"

    /** Tab 2. 记录 — session list. */
    const val SESSIONS = "sessions"

    /** Tab 3. 任务工作台. */
    const val TASKS = "tasks"

    /** Tab 4. 我的. */
    const val ME = "me"

    // -- the record key ------------------------------------------------------

    /** Long press on the record key: 实时录音 · 会议模式. */
    const val RECORDING = "recording"

    // -- 记录 branch ---------------------------------------------------------

    /**
     * 记录 › a session. Tabs inside: 转写 / 总结 / 导图.
     *
     * `seek` is the millisecond offset a "↩ 回到原声" chip is pointing at. It is
     * part of the route rather than screen state because the jump originates from
     * a different screen every time — a summary line, an insight, a task, a report
     * paragraph — and none of them can reach into the detail screen's state.
     */
    const val SESSION_DETAIL = "session/{sessionId}?seek={seek}"

    /** 首页 hero「看三种声音」, and 会话详情 › 洞察. */
    const val INSIGHTS = "insights"

    /** 洞察 ›「生成报告」. */
    const val REPORT = "report/{sessionId}"

    /** 记录 › 周/月, and the Sunday review card on 今日谛听. */
    const val REVIEW = "review/{period}"

    /** 转写搜索 — 记录 › 搜索. */
    const val SEARCH = "search"

    // -- 任务 branch ---------------------------------------------------------

    /** 任务 › a task. Shows the gate, the execution trail and the destination. */
    const val TASK_DETAIL = "task/{taskId}"

    /** 首页 AI 球, and 任务 › 对话. */
    const val CHAT = "chat"

    // -- 我的 branch ---------------------------------------------------------

    /** 我的 › 设备管理. */
    const val DEVICES = "devices"

    /** 我的 › 大脑账户 — login, device adoption, channel status. */
    const val BRAIN = "brain"

    /** 设备管理 › 添加设备. */
    const val PAIRING = "pairing"

    /** 我的 › 能力与订阅. */
    const val SUBSCRIPTION = "subscription"

    /** 我的 › 教小谛 — the hub over the four layers below. */
    const val TEACH = "teach"

    /** 教小谛 › 听感 · 声纹与口音, and auto-entered after first pairing. */
    const val ONBOARDING = "onboarding"

    /** 教小谛 › 听感 · 热词与专名, and 转写「纠错」. */
    const val HOTWORDS = "hotwords"

    /** 教小谛 › 理解 · 场景与响应规则. */
    const val SCENES = "scenes"

    /** 教小谛 › 行动 · 模型与 Skill, and 任务详情「所用 Skill」. */
    const val MODELS = "models"

    /** 教小谛 › 行动 · 目的地与通道, and 任务详情「管理目的地」. */
    const val DESTINATIONS = "destinations"

    /** 教小谛 › 记忆 · 记忆与遗忘. */
    const val MEMORY = "memory"

    /** 我的 › 成长. */
    const val GROWTH = "growth"

    /** @param seekMs offset to jump to, or null to open at the top. */
    fun sessionDetail(sessionId: String, seekMs: Long? = null) =
        "session/$sessionId?seek=${seekMs ?: NO_SEEK}"

    /** Sentinel for "no jump requested"; nav arguments cannot be null Longs. */
    const val NO_SEEK = -1L
    fun report(sessionId: String) = "report/$sessionId"
    fun taskDetail(taskId: String) = "task/$taskId"
    fun review(period: ReviewPeriod) = "review/${period.name.lowercase()}"
}

enum class ReviewPeriod {
    /** 周 · 对表 — the commitment ledger. */
    WEEK,

    /** 月 · 沉淀 — decision review and what to forget. */
    MONTH;

    companion object {
        fun parse(raw: String?): ReviewPeriod =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: WEEK
    }
}
