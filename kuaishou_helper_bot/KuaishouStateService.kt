package com.helper.kuaishou

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 快手状态读取服务 - 仅读取，不执行任何点击/滑动操作
 *
 * 关键安全配置：
 * - canPerformGestures = false (在 xml 中配置)
 * - 不调用任何 performAction(ACTION_CLICK / ACTION_SCROLL)
 */
class KuaishouStateService : AccessibilityService() {

    companion object {
        private const val TAG = "KSSStateService"
        // 快手的包名
        private const val PKG_KUAISHOU = "com.kuaishou.nebula"
        private const val PKG_KUAISHOU_LITE = "com.kuaishou.nebula.lite"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 配置服务信息 - 只读模式
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 1500
            packageNames = arrayOf(PKG_KUAISHOU, PKG_KUAISHOU_LITE)
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        Logger.i(TAG, "✅ 状态读取服务已连接（只读模式）")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // 只处理快手的事件
        val pkg = event.packageName?.toString() ?: return
        if (pkg != PKG_KUAISHOU && pkg != PKG_KUAISHOU_LITE) return

        val root = rootInActiveWindow ?: return

        // === 核心：只读取，不操作 ===
        val state = readPageState(root)

        // 释放节点引用，避免内存泄漏
        root.recycle()

        // 发送给决策引擎
        DecisionEngine.evaluate(state)
    }

    /**
     * 读取页面状态 - 纯读取操作
     */
    private fun readPageState(root: AccessibilityNodeInfo): PageState {
        return PageState(
            timestamp = System.currentTimeMillis(),
            pageType = detectPageType(root),
            coinAmount = extractCoinAmount(root),
            progressPercent = extractProgress(root),
            adRemainingSeconds = extractAdCountdown(root),
            canClaim = detectClaimableButton(root),
            uiSnapshot = snapshotUiElements(root)
        )
    }

    /**
     * 页面类型识别
     */
    private fun detectPageType(root: AccessibilityNodeInfo): PageType {
        val text = getAllText(root)
        return when {
            text.contains("广告") || text.contains("了解详情") -> PageType.VIDEO_AD
            text.contains("宝箱") || text.contains("开宝箱") -> PageType.TREASURE_BOX
            text.contains("任务") || text.contains("每日任务") -> PageType.TASK_PAGE
            text.contains("提现") -> PageType.WITHDRAW_PAGE
            hasVideoPlayer(root) -> PageType.VIDEO_PLAYING
            else -> PageType.UNKNOWN
        }
    }

    /**
     * 提取金币数量
     */
    private fun extractCoinAmount(root: AccessibilityNodeInfo): Double? {
        // 查找包含 "金币" 文本的节点
        val nodes = findNodesByTextPattern(root, Regex("\\d+\\.?\\d*\\s*金币"))
        return nodes.firstOrNull()?.let { node ->
            Regex("\\d+\\.?\\d*").find(node.text.toString())?.value?.toDoubleOrNull()
        }
    }

    /**
     * 提取进度条百分比
     */
    private fun extractProgress(root: AccessibilityNodeInfo): Int {
        // 查找进度条节点，读取其 rangeInfo
        val progressBar = findFirstNodeByClassName(root, "android.widget.ProgressBar")
        return progressBar?.rangeInfo?.current?.toInt() ?: 0
    }

    /**
     * 提取广告倒计时
     */
    private fun extractAdCountdown(root: AccessibilityNodeInfo): Int? {
        val nodes = findNodesByTextPattern(root, Regex("\\d+\\s*秒"))
        return nodes.firstOrNull()?.let { node ->
            Regex("\\d+").find(node.text.toString())?.value?.toInt()
        }
    }

    /**
     * 检测是否有可领取按钮（仅读取 enabled 状态，不点击）
     */
    private fun detectClaimableButton(root: AccessibilityNodeInfo): Boolean {
        val claimNodes = findNodesByText(root, "领金币") + findNodesByText(root, "领取")
        return claimNodes.any { it.isEnabled && it.isClickable }
    }

    // ========== 辅助方法 ==========

    private fun getAllText(root: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        traverseText(root, sb)
        return sb.toString()
    }

    private fun traverseText(node: AccessibilityNodeInfo, sb: StringBuilder) {
        node.text?.let { sb.append(it).append(" ") }
        for (i in 0 until node.childCount) {
            traverseText(node.getChild(i), sb)
        }
    }

    private fun findNodesByText(root: AccessibilityNodeInfo, text: String): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        traverseFindByText(root, text, result)
        return result
    }

    private fun traverseFindByText(node: AccessibilityNodeInfo, text: String, result: MutableList<AccessibilityNodeInfo>) {
        node.text?.let {
            if (it.toString().contains(text)) result.add(node)
        }
        for (i in 0 until node.childCount) {
            traverseFindByText(node.getChild(i), text, result)
        }
    }

    private fun findNodesByTextPattern(root: AccessibilityNodeInfo, pattern: Regex): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        traverseFindByPattern(root, pattern, result)
        return result
    }

    private fun traverseFindByPattern(node: AccessibilityNodeInfo, pattern: Regex, result: MutableList<AccessibilityNodeInfo>) {
        node.text?.let {
            if (pattern.containsMatchIn(it.toString())) result.add(node)
        }
        for (i in 0 until node.childCount) {
            traverseFindByPattern(node.getChild(i), pattern, result)
        }
    }

    private fun findFirstNodeByClassName(root: AccessibilityNodeInfo, className: String): AccessibilityNodeInfo? {
        if (root.className == className) return root
        for (i in 0 until root.childCount) {
            val found = findFirstNodeByClassName(root.getChild(i), className)
            if (found != null) return found
        }
        return null
    }

    private fun hasVideoPlayer(root: AccessibilityNodeInfo): Boolean {
        return findFirstNodeByClassName(root, "android.view.TextureView") != null ||
               findFirstNodeByClassName(root, "android.view.SurfaceView") != null
    }

    private fun snapshotUiElements(root: AccessibilityNodeInfo): List<UiElement> {
        val elements = mutableListOf<UiElement>()
        traverseSnapshot(root, elements, depth = 0)
        return elements.take(50) // 限制数量
    }

    private fun traverseSnapshot(node: AccessibilityNodeInfo, elements: MutableList<UiElement>, depth: Int) {
        if (depth > 5) return
        if (node.text != null || node.contentDescription != null) {
            elements.add(
                UiElement(
                    className = node.className?.toString() ?: "",
                    text = node.text?.toString(),
                    contentDesc = node.contentDescription?.toString(),
                    clickable = node.isClickable,
                    enabled = node.isEnabled,
                    depth = depth
                )
            )
        }
        for (i in 0 until node.childCount) {
            traverseSnapshot(node.getChild(i), elements, depth + 1)
        }
    }

    override fun onInterrupt() {
        Logger.i(TAG, "服务中断")
    }
}
