# 快手极速版 · 合规提醒型智能体设计方案

> **核心理念**：AI 看屏幕 → 分析状态 → 文字/语音提醒用户操作 → 用户手动点击  
> **不做什么**：不模拟点击、不抓包、不自动滑屏、不群控、不绕过任何检测  
> **法律安全**：仅使用 Android 无障碍服务的「读取」能力 + 屏幕截图分析，符合《用户协议》对辅助工具的定义

---

## 一、整体架构

```
┌─────────────────────────────────────────────────────────┐
│                     用户（真人）                         │
│                  ↕ 看到提醒后手动操作                    │
└─────────────────────────────────────────────────────────┘
                           ↓ 提醒
┌─────────────────────────────────────────────────────────┐
│               提醒引擎（Notification）                    │
│    - 状态变化检测 → 推送通知/语音播报                    │
│    - 频率控制：避免骚扰（每 30s 最多 1 次）              │
└─────────────────────────────────────────────────────────┘
                           ↑ 调用
┌─────────────────────────────────────────────────────────┐
│              决策大脑（LLM + 规则引擎）                  │
│    - 输入：页面状态 JSON                                 │
│    - 输出：{ action: "提醒滑走"|"提醒领金币"|"建议休息" }│
│    - 规则层：金币时段策略、防沉迷提醒                    │
└─────────────────────────────────────────────────────────┘
                           ↑ 消费
┌─────────────────────────────────────────────────────────┐
│              状态感知层（State Perception）              │
│    - 无障碍节点读取（仅 text/resource-id/状态）          │
│    - 屏幕截图 → OCR/UI 检测 → 结构化页面状态             │
│    - 不点击、不滑动、不注入任何事件                      │
└─────────────────────────────────────────────────────────┘
                           ↑ 读取
┌─────────────────────────────────────────────────────────┐
│                    快手极速版 App                        │
│             （正常运行，无任何篡改）                      │
└─────────────────────────────────────────────────────────┘
```

---

## 二、模块设计

### 模块 1：状态感知层（State Perception）

**职责**：只读不写，把当前屏幕「看懂」

| 能力 | 技术选型 | 说明 |
|------|----------|------|
| 无障碍节点读取 | Android AccessibilityService | 仅读取 TextView 文本、按钮状态、进度条百分比 |
| 屏幕截图 | MediaProjection API | 定时截屏（如每 10s 一次） |
| 页面分类 | 轻量 CNN / 模板匹配 | 区分「视频页/广告页/宝箱页/任务页/提现页」 |
| 关键元素 OCR | Tesseract / PaddleOCR-Mobile | 识别金币数字、倒计时、任务进度 |

**输出示例**：
```json
{
  "page_type": "video_ad",
  "is_playing": true,
  "progress_percent": 73,
  "coin_visible": true,
  "coin_amount": "158.3",
  "ad_remaining_seconds": 8,
  "can_claim": false,
  "ui_elements": [
    {"id": "com.kuaishou.nebula:id/coin_icon", "text": "领金币", "enabled": false},
    {"id": "com.kuaishou.nebula:id/progress_bar", "progress": 73}
  ]
}
```

### 模块 2：决策大脑（Decision Engine）

**职责**：根据状态决定「该提醒用户做什么」

```python
# 伪代码
def decide(state: PageState) -> ReminderAction:
    # 规则 1：广告快播完 → 提醒准备领金币
    if state.page_type == "video_ad" and state.ad_remaining_seconds <= 5:
        return ReminderAction(
            type="notify",
            message="广告即将播完，准备好点击领金币 🪙",
            urgency="high"
        )
    
    # 规则 2：宝箱可领取
    if state.page_type == "treasure_box" and state.can_claim:
        return ReminderAction(
            type="notify",
            message="🎁 宝箱可以领取了！点击开宝箱",
            urgency="high"
        )
    
    # 规则 3：视频正常播放中 → 不提醒，静默等待
    if state.page_type == "video" and state.is_playing:
        return ReminderAction(type="silent")
    
    # 规则 4：疑似卡住（同一页面超过 2 分钟）
    if state.stuck_duration > 120:
        return ReminderAction(
            type="suggest",
            message="似乎卡住了，建议上滑切换视频",
            urgency="medium"
        )
    
    # 规则 5：防沉迷（连续使用 > 30 分钟）
    if state.session_duration > 1800:
        return ReminderAction(
            type="warn",
            message="⏰ 已观看 30 分钟，建议休息 5 分钟再继续",
            urgency="low"
        )
    
    return ReminderAction(type="silent")
```

**LLM 增强**（可选）：
- 当规则引擎无法判断时，把截图 + 状态 JSON 发给云端 LLM（如腾讯混元），让它给出建议
- Prompt 模板：`"你是一个手机使用助手，以下是用户当前屏幕的状态，请给出最合理的操作建议（不超过 20 字）"`

### 模块 3：提醒引擎（Notification Engine）

**职责**：把决策结果以不打扰的方式推给用户

| 通道 | 场景 | 频率限制 |
|------|------|----------|
| 系统通知栏 | 宝箱可领、广告结束 | 每 30s 最多 1 条 |
| 语音播报（TTS） | 用户戴耳机/不看屏 | 同上 |
| 悬浮窗提示 | App 在前台时 | 每 15s 最多 1 次 |
| 振动 | 高优先级提醒 | 仅宝箱/领金币 |

**防骚扰机制**：
- 用户 5 秒内无响应 → 自动降级为低优先级
- 用户连续 3 次忽略 → 暂停提醒 5 分钟
- 夜间模式（22:00–07:00）→ 仅振动 + 不亮屏

---

## 三、技术选型清单

| 层级 | 推荐方案 | 备选 | 说明 |
|------|----------|------|------|
| 运行环境 | Android App（Kotlin） | Flutter 跨平台 | 无障碍服务需原生 |
| 无障碍读取 | AccessibilityService | — | 系统级 API，无需 root |
| 截屏 | MediaProjection + ImageReader | — | Android 5.0+ 支持 |
| 端侧 OCR | PaddleOCR-Mobile | ML Kit Text Recognition | 轻量、离线 |
| 页面分类 | TensorFlow Lite（MobileNet） | 模板匹配 | 端侧推理 < 100ms |
| 规则引擎 | Kotlin 原生 | Drools | 轻量规则无需重量级 |
| LLM 调用 | 腾讯混元 API | 本地小模型（Phi-3-mini） | 网络好用云端，离线用小模型 |
| 通知 | NotificationManager + TTS | — | 系统标准 API |
| 数据看板 | 本地 SQLite + MPAndroidChart | — | 记录每日金币趋势 |

---

## 四、关键代码框架

### 4.1 AccessibilityService 配置（仅读取）

```kotlin
// KuaishouStateService.kt
class KuaishouStateService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // ⚠️ 只读取，绝不调用 performAction(ACTION_CLICK)
        val root = rootInActiveWindow ?: return
        
        val state = PageState(
            pageType = detectPageType(root),
            isPlaying = findProgressBar(root)?.isProgressing() ?: false,
            progressPercent = findProgressBar(root)?.getPercent() ?: 0,
            coinAmount = findCoinText(root)?.text?.toString(),
            canClaim = findClaimButton(root)?.isEnabled ?: false,
            uiElements = extractUiElements(root)
        )
        
        // 发送给决策引擎
        DecisionEngine.evaluate(state)
    }

    override fun onInterrupt() { /* no-op */ }
    
    // 关键：不重写任何 performAction，不注入手势
}
```

### 4.2 无障碍配置 XML（声明仅读取权限）

```xml
<!-- res/xml/accessibility_service_config.xml -->
<accessibility-service
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged"
    android:accessibilityFlags="flagDefault|flagRetrieveInteractiveWindows"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:notificationTimeout="1000"
    android:description="@string/service_description"
    android:canRetrieveWindowContent="true"
    android:canPerformGestures="false"        <!-- 关键：禁止手势 -->
    android:canRequestTouchExploration="false" <!-- 关键：禁止触摸模拟 -->
/>
```

> **注意 `canPerformGestures="false"`** —— 这是和违规脚本的根本区别，系统层面就禁止了模拟点击。

### 4.3 决策引擎

```kotlin
// DecisionEngine.kt
object DecisionEngine {
    private val rules = listOf(
        AdFinishingRule(),
        TreasureBoxReadyRule(),
        VideoPlayingRule(),
        StuckDetectionRule(),
        AntiAddictionRule()
    )
    
    fun evaluate(state: PageState) {
        val action = rules
            .firstOrNull { it.matches(state) }
            ?.execute(state) ?: ReminderAction.SILENT
            
        if (action.type != "silent") {
            NotificationEngine.dispatch(action)
        }
    }
}
```

### 4.4 提醒分发

```kotlin
// NotificationEngine.kt
object NotificationEngine {
    private var lastNotifyTime = 0L
    private const val MIN_INTERVAL_MS = 30_000L
    
    fun dispatch(action: ReminderAction) {
        val now = System.currentTimeMillis()
        if (now - lastNotifyTime < MIN_INTERVAL_MS && action.urgency != "high") {
            return  // 频率限制
        }
        
        when (action.type) {
            "notify" -> showNotification(action.message)
            "suggest" -> showFloatingTip(action.message)
            "warn" -> showNotificationWithVibration(action.message)
        }
        
        lastNotifyTime = now
    }
}
```

---

## 五、金币数据看板（增值功能）

合规且有用，纯本地记录：

```
📊 今日金币统计
┌─────────────────────────────┐
│  累计金币：2,847 🪙         │
│  预估收益：¥0.28            │
│  有效观看：42 分钟          │
│  宝箱领取：6/8              │
├─────────────────────────────┤
│  时段收益曲线               │
│  09:00 ████ 120 🪙         │
│  10:00 ████ 95 🪙          │
│  11:00 ██ 60 🪙            │
│  ...                        │
└─────────────────────────────┘
```

数据来源：读取屏幕上金币数字的变化差值 → 记录到本地 SQLite → 生成图表。

---

## 六、与「违规脚本」的边界对照表

| 行为 | 违规脚本 | 本方案（合规） |
|------|----------|----------------|
| 点击按钮 | ✅ 自动点击 | ❌ 不点击，只提醒 |
| 滑动屏幕 | ✅ 自动滑 | ❌ 不滑，建议用户滑 |
| 无障碍手势 | ✅ 使用 | ❌ 配置禁用 |
| 读取屏幕内容 | ✅ 顺便读 | ✅ 唯一用途 |
| 抓包/协议伪造 | ✅ 常见 | ❌ 完全不涉及 |
| 多开/群控 | ✅ 工作室用 | ❌ 单设备单号 |
| 绕过检测 | ✅ 核心功能 | ❌ 不绕过任何东西 |
| 用户协议 | 违反 | 遵守 |

---

## 七、落地步骤

1. **第一阶段（1-2 天）**：搭 Android 项目骨架，注册 AccessibilityService，验证能读到快手页面节点文本
2. **第二阶段（2-3 天）**：接 MediaProjection 截屏，跑通页面分类模型（先用模板匹配，再上 TFLite）
3. **第三阶段（1-2 天）**：写规则引擎 + 通知分发，完成「广告结束提醒」「宝箱提醒」两个核心场景
4. **第四阶段（可选）**：接 LLM API 做模糊场景判断，加本地数据看板

---

## 八、重要声明

> ⚠️ **使用前提**：本工具仅读取屏幕信息并提醒，**所有操作由用户手动完成**。  
> ⚠️ **禁止改造**：不得将本方案扩展为自动点击/自动滑动/群控版本，否则将违反平台协议并可能触犯法律。  
> ⚠️ **账号风险自担**：即使合规使用，频繁调用无障碍服务仍可能被平台检测，建议低频使用。

---

*Generated by 元宝 · AI 智能体设计方案*
