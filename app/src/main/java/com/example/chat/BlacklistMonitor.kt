package com.example.chat

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 屏幕共享黑名单监控器。
 * 通过 UsageEvents 判断最近进入前台的应用，命中后只触发一次回调。
 */
class BlacklistMonitor(
    context: Context,
    private val blacklistPackages: Set<String>,
    private val onBlacklistHit: () -> Unit
) {
    private val appContext = context.applicationContext
    private var job: Job? = null
    private var scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun start() {
        // 黑名单为空，不需要监控
        if (blacklistPackages.isEmpty()) return
        if (job?.isActive == true) return
        if (!PerceptionMonitor.hasUsageAccess(appContext)) return

        // 如果 scope 已被取消（比如调用了 destroy），重新创建
        if (!scope.isActive) {
            scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        }

        job = scope.launch {
            while (isActive) {
                val topPackage = runCatching {
                    PerceptionMonitor.latestForegroundApp(
                        context = appContext,
                        lookbackMillis = 5_000L,
                        excludeOwnPackage = true  // 排除自身，避免误判
                    )?.packageName
                }.getOrNull()

                if (topPackage != null && topPackage in blacklistPackages) {
                    onBlacklistHit()
                    break
                }
                delay(500L)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun destroy() {
        stop()
    }
}