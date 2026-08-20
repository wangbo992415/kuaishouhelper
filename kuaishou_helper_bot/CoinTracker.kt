package com.helper.kuaishou

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.*

/**
 * 金币数据追踪器
 *
 * 功能：
 * - 记录每次金币变化
 * - 统计每日/每小时收益
 * - 生成趋势数据供看板展示
 *
 * 数据来源：状态感知层读取到的金币数字变化
 */
class CoinTracker(context: Context) {

    private val dbHelper = CoinDatabase(context)

    /**
     * 记录金币快照
     */
    fun recordSnapshot(totalCoins: Double, source: String = "auto") {
        val db = dbHelper.writableDatabase
        val now = System.currentTimeMillis()
        val hourBucket = getHourBucket(now)

        // 计算本次增量
        val lastTotal = getLastTotal(db)
        val delta = if (lastTotal > 0) (totalCoins - lastTotal) else 0.0

        val values = ContentValues().apply {
            put("timestamp", now)
            put("hour_bucket", hourBucket)
            put("total_coins", totalCoins)
            put("delta_coins", delta)
            put("source", source)
        }
        db.insert("coin_log", null, values)

        // 同时更新小时统计
        updateHourlyStats(db, hourBucket, delta)

        db.close()
    }

    /**
     * 获取今日统计
     */
    fun getTodayStats(): DailyStats {
        val db = dbHelper.readableDatabase
        val todayStart = getTodayStartMillis()
        val cursor = db.rawQuery(
            "SELECT SUM(delta_coins), COUNT(*), MAX(total_coins) FROM coin_log WHERE timestamp >= ?",
            arrayOf(todayStart.toString())
        )

        var totalEarned = 0.0
        var recordCount = 0
        var currentTotal = 0.0

        cursor.use {
            if (it.moveToFirst()) {
                totalEarned = it.getDouble(0)
                recordCount = it.getInt(1)
                currentTotal = it.getDouble(2)
            }
        }

        // 获取今日活跃时长（分钟）
        val activeMinutes = getActiveMinutes(db, todayStart)

        db.close()

        return DailyStats(
            date = SimpleDateFormat("MM-dd", Locale.CHINA).format(Date()),
            totalEarned = totalEarned,
            estimatedRmb = totalEarned / 10000.0 * 100, // 大致换算
            recordCount = recordCount,
            activeMinutes = activeMinutes,
            currentTotal = currentTotal
        )
    }

    /**
     * 获取小时级趋势（用于折线图）
     */
    fun getHourlyTrend(hours: Int = 12): List<HourlyPoint> {
        val db = dbHelper.readableDatabase
        val since = System.currentTimeMillis() - hours * 3600 * 1000L

        val cursor = db.rawQuery(
            "SELECT hour_bucket, SUM(delta) FROM hourly_stats WHERE hour_bucket >= ? GROUP BY hour_bucket ORDER BY hour_bucket",
            arrayOf(since.toString())
        )

        val result = mutableListOf<HourlyPoint>()
        cursor.use {
            while (it.moveToNext()) {
                result.add(
                    HourlyPoint(
                        hour = it.getLong(0),
                        coins = it.getDouble(1)
                    )
                )
            }
        }
        db.close()
        return result
    }

    /**
     * 获取最佳收益时段
     */
    fun getBestHours(): List<Int> {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            "SELECT strftime('%H', datetime(hour_bucket/1000, 'unixepoch', 'localtime')) as h, " +
            "AVG(delta) as avg_delta FROM hourly_stats GROUP BY h ORDER BY avg_delta DESC LIMIT 3",
            null
        )

        val result = mutableListOf<Int>()
        cursor.use {
            while (it.moveToNext()) {
                result.add(it.getInt(0))
            }
        }
        db.close()
        return result
    }

    // ========== 私有方法 ==========

    private fun getLastTotal(db: SQLiteDatabase): Double {
        val cursor = db.rawQuery(
            "SELECT total_coins FROM coin_log ORDER BY timestamp DESC LIMIT 1",
            null
        )
        var total = 0.0
        cursor.use {
            if (it.moveToFirst()) total = it.getDouble(0)
        }
        return total
    }

    private fun updateHourlyStats(db: SQLiteDatabase, hourBucket: Long, delta: Double) {
        val values = ContentValues().apply {
            put("hour_bucket", hourBucket)
            put("delta", delta)
            put("updated_at", System.currentTimeMillis())
        }

        val rows = db.update("hourly_stats", values, "hour_bucket = ?", arrayOf(hourBucket.toString()))
        if (rows == 0) {
            db.insert("hourly_stats", null, values)
        }
    }

    private fun getActiveMinutes(db: SQLiteDatabase, since: Long): Int {
        val cursor = db.rawQuery(
            "SELECT COUNT(DISTINCT strftime('%M', datetime(timestamp/1000, 'unixepoch'))) FROM coin_log WHERE timestamp >= ?",
            arrayOf(since.toString())
        )
        var minutes = 0
        cursor.use {
            if (it.moveToFirst()) minutes = it.getInt(0)
        }
        return minutes
    }

    private fun getHourBucket(timestamp: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun getTodayStartMillis(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    // ========== 数据类 ==========

    data class DailyStats(
        val date: String,
        val totalEarned: Double,
        val estimatedRmb: Double,
        val recordCount: Int,
        val activeMinutes: Int,
        val currentTotal: Double
    )

    data class HourlyPoint(
        val hour: Long,
        val coins: Double
    )
}

/**
 * 数据库 Helper
 */
class CoinDatabase(context: Context) : SQLiteOpenHelper(context, "coin_tracker.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        // 金币日志表
        db.execSQL("""
            CREATE TABLE coin_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                hour_bucket INTEGER NOT NULL,
                total_coins REAL NOT NULL,
                delta_coins REAL DEFAULT 0,
                source TEXT DEFAULT 'auto'
            )
        """)

        // 小时统计表
        db.execSQL("""
            CREATE TABLE hourly_stats (
                hour_bucket INTEGER PRIMARY KEY,
                delta REAL DEFAULT 0,
                updated_at INTEGER
            )
        """)

        // 索引
        db.execSQL("CREATE INDEX idx_coin_log_time ON coin_log(timestamp)")
        db.execSQL("CREATE INDEX idx_coin_log_hour ON coin_log(hour_bucket)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE coin_log ADD COLUMN source TEXT DEFAULT 'auto'")
        }
    }
}
