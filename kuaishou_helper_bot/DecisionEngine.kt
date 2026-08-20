package com.helper.kuaishou

import android.util.Log

/**
 * 页面状态数据类
 */
data class PageState(
    val timestamp: Long,
    val pageType: PageType,
    val coinAmount: Double? = null,
    val progressPercent: Int = 0,
    val adRemainingSeconds: Int? = null,
    val canClaim: Boolean = false,
    val uiSnapshot: List<UiElement> = emptyList()
)

/**
 * 页面类型枚举
 */
enum class PageType {
    VIDEO_PLAYING,   // 正常视频播放
    VIDEO_AD,        // 广告视频
    TREASURE_BOX,    // 宝箱页面
    TASK_PAGE,       // 任务页面
    WITHDRAW_PAGE,   // 提现页面
    UNKNOWN          // 无法识别
}

/**
 * UI 元素快照
 */
data class UiElement(
    val className: String,
    val text: String?,
    val contentDesc: String?,
    val clickable: Boolean,
    val enabled: Boolean,
    val depth: Int
)

/**
 * 提醒动作
 */
data class ReminderAction(
    val type: ActionType,
    val message: String,
    val urgency: Urgency = Urgency.NORMAL
) {
    enum class ActionType { SILENT, NOTIFY, SUGGEST, WARN, VIBRATE }
    enum class Urgency { LOW, NORMAL, HIGH }

    companion object {
        val SILENT = ReminderAction(ActionType.SILENT, "")
    }
}

/**
 * 决策引擎 - 规则驱动
 *
 * 设计原则：
 * - 优先级从高到低匹配（第一个命中的规则生效）
 * - 每条规则只输出"建议"，不执行任何操作
 * - 规则可热更新（从本地配置/远程下发）
 */
object DecisionEngine {

    private const val TAG = "DecisionEngine"

    // 会话追踪
    private var sessionStart = System.currentTimeMillis()
    private var lastPageType: PageType? = null
    private var pageEnterTime: Long = System.currentTimeMillis()
    private var lastCoinAmount: Double? = null
    private var stuckCount = 0

    /**
     * 规则列表（按优先级排序）
     */
    private val rules: List<Rule> = listOf(
        AntiAddictionRule(),       // P0: 防沉迷最高优先级
        AdFinishingRule(),         // P1: 广告快结束
        TreasureBoxReadyRule(),    // P2: 宝箱可领
        ClaimButtonReadyRule(),    // P3: 领金币按钮可用
        StuckDetectionRule(),      // P4: 疑似卡住
        CoinChangeRule(),          // P5: 金币变化提醒
        VideoPlayingRule()         // P6: 正常播放-静默
    )

    fun evaluate(state: PageState) {
        // 更新会话状态
        updateSession(state)

        // 按优先级匹配规则
        for (rule in rules) {
            if (rule.matches(state)) {
                val action = rule.execute(state)
                if (action.type != ReminderAction.ActionType.SILENT) {
                    Log.d(TAG, "📋 规则 [${rule.name}] 触发 → ${action.message}")
                    NotificationEngine.dispatch(action)
                }
                return
            }
        }
    }

    private fun updateSession(state: PageState) {
        // 页面类型变化检测
        if (state.pageType != lastPageType) {
            pageEnterTime = System.currentTimeMillis()
            stuckCount = 0
            lastPageType = state.pageType
        } else {
            // 同一页面超过 90 秒算疑似卡住
            val duration = System.currentTimeMillis() - pageEnterTime
            if (duration > 90_000) stuckCount++
        }

        // 金币变化追踪
        state.coinAmount?.let { current ->
            lastCoinAmount = current
        }
    }

    fun resetSession() {
        sessionStart = System.currentTimeMillis()
        lastPageType = null
        pageEnterTime = System.currentTimeMillis()
        stuckCount = 0
    }

    // ==================== 规则定义 ====================

    abstract class Rule(val name: String) {
        abstract fun matches(state: PageState): Boolean
        abstract fun execute(state: PageState): ReminderAction
    }

    /**
     * P0: 防沉迷规则 - 连续使用超过 30 分钟提醒休息
     */
    class AntiAddictionRule : Rule("防沉迷") {
        override fun matches(state: PageState): Boolean {
            val sessionMs = System.currentTimeMillis() - sessionStart
            return sessionMs > 30 * 60 * 1000
        }

        override fun execute(state: PageState): ReminderAction {
            return ReminderAction(
                type = ReminderAction.ActionType.WARN,
                message = "⏰ 已观看 30 分钟，建议休息 5 分钟，保护眼睛~",
                urgency = ReminderAction.Urgency.LOW
            )
        }
    }

    /**
     * P1: 广告快结束（剩余 ≤ 5 秒）
     */
    class AdFinishingRule : Rule("广告结束提醒") {
        override fun matches(state: PageState): Boolean {
            return state.pageType == PageType.VIDEO_AD &&
                   state.adRemainingSeconds != null &&
                   state.adRemainingSeconds!! <= 5
        }

        override fun execute(state: PageState): ReminderAction {
            return ReminderAction(
                type = ReminderAction.ActionType.NOTIFY,
                message = "🎬 广告即将播完，准备好领取金币！",
                urgency = ReminderAction.Urgency.HIGH
            )
        }
    }

    /**
     * P2: 宝箱可领取
     */
    class TreasureBoxReadyRule : Rule("宝箱提醒") {
        override fun matches(state: PageState): Boolean {
            return state.pageType == PageType.TREASURE_BOX && state.canClaim
        }

        override fun execute(state: PageState): ReminderAction {
            return ReminderAction(
                type = ReminderAction.ActionType.NOTIFY,
                message = "🎁 宝箱可以领取了！点击开启 →",
                urgency = ReminderAction.Urgency.HIGH
            )
        }
    }

    /**
     * P3: 领金币按钮可用
     */
    class ClaimButtonReadyRule : Rule("领金币按钮") {
        override fun matches(state: PageState): Boolean {
            return state.canClaim && state.pageType != PageType.TREASURE_BOX
        }

        override fun execute(state: PageState): ReminderAction {
            return ReminderAction(
                type = ReminderAction.ActionType.NOTIFY,
                message = "🪙 可以领取金币了，点击领取按钮",
                urgency = ReminderAction.Urgency.HIGH
            )
        }
    }

    /**
     * P4: 疑似卡住（同一页面超过 2 分钟）
     */
    class StuckDetectionRule : Rule("卡住检测") {
        override fun matches(state: PageState): Boolean {
            val duration = System.currentTimeMillis() - pageEnterTime
            return duration > 120_000 && stuckCount >= 1
        }

        override fun execute(state: PageState): ReminderAction {
            return ReminderAction(
                type = ReminderAction.ActionType.SUGGEST,
                message = "🤔 页面似乎没变化，建议上滑切换一下",
                urgency = ReminderAction.Urgency.NORMAL
            )
        }
    }

    /**
     * P5: 金币变化（每隔一段时间汇报一次收益）
     */
    class CoinChangeRule : Rule("金币变化") {
        private var lastReportTime = 0L
        private var lastReportedCoin = 0.0

        override fun matches(state: PageState): Boolean {
            val now = System.currentTimeMillis()
            val coin = state.coinAmount ?: return false
            val timeOk = now - lastReportTime > 5 * 60 * 1000 // 5分钟报一次
            val changed = kotlin.math.abs(coin - lastReportedCoin) > 10
            return timeOk && changed
        }

        override fun execute(state: PageState): ReminderAction {
            val coin = state.coinAmount!!
            val earned = (coin - lastReportedCoin).toInt()
            lastReportTime = System.currentTimeMillis()
            lastReportedCoin = coin
            return ReminderAction(
                type = ReminderAction.ActionType.SUGGEST,
                message = "📊 当前金币 ${coin.toInt()} 🪙，近 5 分钟赚了 ${earned} 金币",
                urgency = ReminderAction.Urgency.LOW
            )
        }
    }

    /**
     * P6: 正常播放 - 静默
     */
    class VideoPlayingRule : Rule("正常播放") {
        override fun matches(state: PageState): Boolean {
            return state.pageType == PageType.VIDEO_PLAYING ||
                   state.pageType == PageType.VIDEO_AD
        }

        override fun execute(state: PageState): ReminderAction {
            return ReminderAction.SILENT
        }
    }
}
