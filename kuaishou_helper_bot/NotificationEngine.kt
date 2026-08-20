package com.helper.kuaishou

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 提醒分发引擎
 *
 * 功能：
 * - 系统通知栏推送
 * - 振动提醒
 * - 频率控制（防骚扰）
 * - 夜间模式自动降级
 */
object NotificationEngine {

    private const val TAG = "NotifEngine"
    private const val CHANNEL_ID = "kuaishou_helper_channel"
    private const val CHANNEL_NAME = "快手助手提醒"
    private const val MIN_INTERVAL_MS = 30_000L   // 最短通知间隔 30 秒
    private const val HIGH_MIN_INTERVAL_MS = 5_000L // 高优先级最短间隔 5 秒

    private var lastNotifyTime = 0L
    private var ignoreCount = 0
    private var pausedUntil = 0L

    private lateinit var appContext: Context
    private lateinit var notificationManager: NotificationManager
    private val mainHandler = Handler(Looper.getMainLooper())

    fun init(context: Context) {
        appContext = context.applicationContext
        notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        Log.d(TAG, "✅ 通知引擎初始化完成")
    }

    /**
     * 分发提醒
     */
    fun dispatch(action: ReminderAction) {
        // 检查是否暂停中
        if (System.currentTimeMillis() < pausedUntil) {
            Log.d(TAG, "⏸ 提醒暂停中，跳过")
            return
        }

        // 频率控制
        val interval = if (action.urgency == ReminderAction.Urgency.HIGH) {
            HIGH_MIN_INTERVAL_MS
        } else {
            MIN_INTERVAL_MS
        }

        val now = System.currentTimeMillis()
        if (now - lastNotifyTime < interval) {
            Log.d(TAG, "⏳ 频率限制，跳过: ${action.message}")
            return
        }

        // 夜间模式检测（22:00 - 07:00）
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val isNightMode = hour >= 22 || hour < 7

        when (action.type) {
            ReminderAction.ActionType.NOTIFY -> {
                if (isNightMode) {
                    // 夜间只振动，不亮屏不发声
                    vibrate(500)
                } else {
                    showNotification(action.message, action.urgency)
                    if (action.urgency == ReminderAction.Urgency.HIGH) vibrate(300)
                }
            }
            ReminderAction.ActionType.WARN -> {
                if (!isNightMode) {
                    showNotification(action.message, action.urgency)
                    vibrate(200)
                }
            }
            ReminderAction.ActionType.SUGGEST -> {
                if (!isNightMode) {
                    showNotification(action.message, action.urgency)
                }
            }
            ReminderAction.ActionType.VIBRATE -> {
                vibrate(500)
            }
            ReminderAction.ActionType.SILENT -> { /* no-op */ }
        }

        lastNotifyTime = now
    }

    /**
     * 显示系统通知
     */
    private fun showNotification(message: String, urgency: ReminderAction.Urgency) {
        val priority = when (urgency) {
            ReminderAction.Urgency.HIGH -> NotificationCompat.PRIORITY_HIGH
            ReminderAction.Urgency.NORMAL -> NotificationCompat.PRIORITY_DEFAULT
            ReminderAction.Urgency.LOW -> NotificationCompat.PRIORITY_LOW
        }

        val intent = Intent(appContext, HelperOverlayActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("🪙 快手助手")
            .setContentText(message)
            .setPriority(priority)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .build()

        val notifId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        notificationManager.notify(notifId, notification)
        Log.d(TAG, "📢 通知: $message")
    }

    /**
     * 振动
     */
    private fun vibrate(durationMs: Long) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        } catch (e: Exception) {
            Log.w(TAG, "振动失败: ${e.message}")
        }
    }

    /**
     * 用户忽略提醒后的反馈
     * 连续 3 次忽略 → 暂停 5 分钟
     */
    fun reportIgnored() {
        ignoreCount++
        if (ignoreCount >= 3) {
            pausedUntil = System.currentTimeMillis() + 5 * 60 * 1000
            ignoreCount = 0
            Log.d(TAG, "⏸ 用户连续忽略 3 次，暂停提醒 5 分钟")
        }
    }

    /**
     * 用户响应了提醒（点击通知）
     */
    fun reportResponded() {
        ignoreCount = 0
    }

    /**
     * 创建通知渠道（Android 8.0+）
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "快手助手的状态提醒通知"
                enableVibration(true)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
}
