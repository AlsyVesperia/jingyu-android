package com.example.chat

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Process
import android.util.Log

/**
 * 统一管理“亮屏/解锁”和“前台应用”感知。
 *
 * 亮屏：使用广播记录状态变化的时刻，不再在屏幕持续点亮时反复刷新时间。
 * 前台应用：使用 UsageEvents 的 RESUMED / MOVE_TO_FOREGROUND 事件，而不是
 * queryUsageStats().maxBy(lastTimeUsed)，避免把陈旧记录或本应用误判为当前应用。
 */
object PerceptionMonitor {
    private const val TAG = "PerceptionMonitor"
    private const val KEY_LAST_SCREEN_ON = "perception_last_screen_on"
    private const val KEY_LAST_USER_PRESENT = "perception_last_user_present"
    private const val KEY_LAST_FOREGROUND_PACKAGE = "perception_last_foreground_package"
    private const val KEY_LAST_FOREGROUND_TIME = "perception_last_foreground_time"

    data class ForegroundSnapshot(
        val packageName: String,
        val timestamp: Long
    )

    @Volatile
    private var receiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val now = System.currentTimeMillis()
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> AppSettings.putLong(KEY_LAST_SCREEN_ON, now)
                Intent.ACTION_USER_PRESENT -> {
                    // 用户完成解锁，比仅仅亮屏更能代表“回到手机”。
                    AppSettings.putLong(KEY_LAST_USER_PRESENT, now)
                    AppSettings.putLong(KEY_LAST_SCREEN_ON, now)
                }
            }
        }
    }

    @Synchronized
    fun init(context: Context) {
        AppSettings.init(context.applicationContext)
        if (receiverRegistered) return

        val appContext = context.applicationContext
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(screenReceiver, filter)
            }
            receiverRegistered = true
        } catch (error: Exception) {
            Log.e(TAG, "注册亮屏感知失败", error)
        }
    }

    fun lastScreenOnTime(): Long = maxOf(
        AppSettings.getLong(KEY_LAST_SCREEN_ON, 0L),
        AppSettings.getLong(KEY_LAST_USER_PRESENT, 0L)
    )

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * 返回时间窗口内最后一个进入前台的应用。默认排除小鲸鱼自身。
     */
    fun latestForegroundApp(
        context: Context,
        lookbackMillis: Long = 2 * 60 * 1000L,
        excludeOwnPackage: Boolean = true
    ): ForegroundSnapshot? {
        if (!hasUsageAccess(context)) return null
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        val now = System.currentTimeMillis()
        val events = manager.queryEvents((now - lookbackMillis).coerceAtLeast(0L), now)
        val event = UsageEvents.Event()
        var latest: ForegroundSnapshot? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val isForegroundEvent = event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            event.eventType == UsageEvents.Event.ACTIVITY_RESUMED)
            if (!isForegroundEvent) continue

            val packageName = event.packageName?.takeIf { it.isNotBlank() } ?: continue
            if (excludeOwnPackage && packageName == context.packageName) continue
            if (latest == null || event.timeStamp >= latest.timestamp) {
                latest = ForegroundSnapshot(packageName, event.timeStamp)
            }
        }
        return latest
    }

    /** 刷新并缓存最近一次外部前台应用事件。 */
    fun refreshLastExternalForeground(context: Context): ForegroundSnapshot? {
        val snapshot = latestForegroundApp(context, excludeOwnPackage = true) ?: return null
        AppSettings.putString(KEY_LAST_FOREGROUND_PACKAGE, snapshot.packageName)
        AppSettings.putLong(KEY_LAST_FOREGROUND_TIME, snapshot.timestamp)
        return snapshot
    }

    fun cachedForeground(maxAgeMillis: Long = 10 * 60 * 1000L): ForegroundSnapshot? {
        val packageName = AppSettings.getString(KEY_LAST_FOREGROUND_PACKAGE, "")
        val timestamp = AppSettings.getLong(KEY_LAST_FOREGROUND_TIME, 0L)
        if (packageName.isBlank() || timestamp <= 0L) return null
        if (System.currentTimeMillis() - timestamp > maxAgeMillis) return null
        return ForegroundSnapshot(packageName, timestamp)
    }

    @Synchronized
    fun release(context: Context) {
        if (!receiverRegistered) return
        try {
            context.applicationContext.unregisterReceiver(screenReceiver)
            receiverRegistered = false
            Log.d(TAG, "PerceptionMonitor 已释放")
        } catch (e: IllegalArgumentException) {
            // 如果接收器未注册或已注销，忽略异常
            Log.w(TAG, "注销广播接收器失败（可能已注销）", e)
            receiverRegistered = false
        }
    }
}

