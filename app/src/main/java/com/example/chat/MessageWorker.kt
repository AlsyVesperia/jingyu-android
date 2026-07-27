package com.example.chat

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit

class MessageWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "MessageWorker"
        private const val MIN_DELAY_MINUTES = 15L
        private const val MAX_DELAY_MINUTES = 45L

        fun scheduleNext(context: Context, sessionId: String) {
            val workManager = WorkManager.getInstance(context)
            val delayMinutes = java.util.concurrent.ThreadLocalRandom.current()
                .nextLong(MIN_DELAY_MINUTES, MAX_DELAY_MINUTES + 1)
            val request = OneTimeWorkRequestBuilder<MessageWorker>()
                .setInputData(Data.Builder().putString("SESSION_ID", sessionId).build())
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .build()
            workManager.enqueueUniqueWork(
                "active_message_$sessionId",
                ExistingWorkPolicy.REPLACE,
                request
            )
            Log.d(TAG, "下次主动消息将在 ${delayMinutes} 分钟后触发")
        }
    }

    override suspend fun doWork(): Result {
        val sessionId = inputData.getString("SESSION_ID") ?: return Result.failure()
        // WorkManager 可能在应用冷启动的独立进程状态中运行，不能假设 Activity 已初始化这些单例。
        AppSettings.init(applicationContext)
        WhisperStore.init(applicationContext)
        PerceptionMonitor.init(applicationContext)
        val dao = DatabaseHolder.get(applicationContext).chatDao()

        return try {
            val session = dao.getSession(sessionId)

            if (session == null || session.isDeleted) {
                // 取消该会话的所有后续定时任务，避免重复唤醒
                WorkManager.getInstance(applicationContext)
                    .cancelUniqueWork("active_message_$sessionId")
                return Result.success()
            }

            val cal = Calendar.getInstance(TimeZone.getTimeZone("GMT+8"))
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            val quietStart = session.quietStartHour
            val quietEnd = session.quietEndHour
            val isQuiet = if (quietStart != quietEnd) {
                if (quietStart < quietEnd) hour in quietStart until quietEnd
                else hour >= quietStart || hour < quietEnd
            } else false
            if (isQuiet) {
                scheduleNext(applicationContext, sessionId)
                return Result.success()
            }

            val recentMessages = dao.getMessagesForSession(sessionId).first().takeLast(10)
            if (recentMessages.isEmpty()) {
                scheduleNext(applicationContext, sessionId)
                return Result.success()
            }

            val chatSummary = recentMessages.joinToString("\n") { msg ->
                val role = if (msg.isUser) session.userName else session.name
                "$role: ${msg.text.take(100)}"
            }

            val systemPrompt = """
你是${session.name}，住在用户手机里的AI朋友。用户的名字是${session.userName}。

这是最近的聊天记录：
$chatSummary

你的任务：
1. 判断是否应该主动给用户发消息。如果不想发，只回复一个单词：NO
2. 如果想发，用非常自然的方式说一句话，表达关心或思念。1-3句话，不超过80字。
3. 可以使用颜文字和emoji，语气像真人朋友。
4. 你也可以选择写一段碎碎念（[WHISPER]内容，3-5句，你的第一人称视角，有感而发时才写，不要每天写）。

如果用户刚刚还在和你聊天，或者没什么特别的事，就回复NO。只在想找用户或有重要提醒时才发消息。
            """.trimIndent()

            val messages = listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to "请决定是否主动联系用户。")
            )

            val requestBody = ChatRequest(
                model = session.modelId.ifBlank { "deepseek-v4-flash" },
                messages = messages,
                max_tokens = session.maxTokens.coerceIn(64, 4096),
                temperature = session.temperature.toDouble(),
                top_p = session.topP.toDouble(),
                thinking = ThinkingConfig.fromEnabled(session.thinkingEnabled)
            )
            val api = DeepSeekClient.createApi(
                apiKey = session.apiKey.trim(),
                apiBaseUrl = session.apiUrl.ifBlank { "https://api.deepseek.com/" })
            val response = api.sendMessage(requestBody)
            var refuseCount = 0
            // 替换原来的 if (response.isSuccessful) { ... } 整个代码块
            if (response.isSuccessful) {
                val aiReply =
                    response.body()?.choices?.firstOrNull()?.message?.content?.trim() ?: ""
                Log.d(TAG, "AI原始回复: $aiReply")

                // 判断是否拒绝了主动联系
                val isNo = aiReply.equals("NO", ignoreCase = true)

                // 读取之前连续拒绝的次数
                val refuseKey = "refuse_count_$sessionId"
                var refuseCount = AppSettings.getInt(refuseKey, 0)

                if (isNo) {
                    // 拒绝次数 +1
                    refuseCount++
                    AppSettings.putInt(refuseKey, refuseCount)
                    Log.d(TAG, "连续拒绝次数: $refuseCount")
                } else {
                    // 如果有回复内容（不是 NO），重置拒绝计数
                    refuseCount = 0
                    AppSettings.putInt(refuseKey, 0)
                }

                // 处理视频通话和碎碎念（这部分不动）
                if (aiReply.contains("[VIDEO_CALL_REQUEST]") && session.activeVideoCall != false) {
                    AppSettings.putBoolean("pending_video_call_${sessionId}", true)
                    MessageNotifier.sendNotification(
                        applicationContext,
                        session.name,
                        "📹 想看看你的屏幕，点击回复",
                        sessionId,
                        session
                    )
                }

                val whisperRegex = Regex("""\[WHISPER](.*)""", RegexOption.IGNORE_CASE)
                val whisperMatch = whisperRegex.find(aiReply)
                if (whisperMatch != null) {
                    val whisperContent = whisperMatch.groupValues[1].trim()
                    if (whisperContent.isNotBlank()) {
                        WhisperStore.add(Whisper(content = whisperContent))
                    }
                }

                // 只有既不是 NO，也不只是空白时，才发送通知和保存消息
                if (!isNo && aiReply.isNotBlank()) {
                    // 过滤掉纯碎碎念（没有实际对话内容）的情况
                    val cleanReply = aiReply.replace(whisperRegex, "").trim()
                    if (cleanReply.isNotBlank() || whisperMatch != null) {
                        // 如果有实际对话内容，插入消息并通知
                        if (cleanReply.isNotBlank()) {
                            dao.insertMessage(
                                MessageEntity(
                                    sessionId = sessionId,
                                    text = aiReply,
                                    isUser = false,
                                    timestamp = System.currentTimeMillis()
                                )
                            )
                            MessageNotifier.sendNotification(
                                applicationContext,
                                session.name,
                                aiReply,
                                sessionId,
                                session
                            )
                        }
                        // 如果只有碎碎念，不重复通知（WhisperStore 已保存）
                    }
                }
            }

// 调度下一次（但根据拒绝次数调整延迟时间）
            val nextDelayMinutes = if (refuseCount >= 3) {
                // 连续拒绝 3 次以上，延长到 2~4 小时（120~240 分钟）
                ThreadLocalRandom.current().nextLong(120, 241)  // 上限不包含，所以用 241
            } else {
                ThreadLocalRandom.current().nextLong(MIN_DELAY_MINUTES, MAX_DELAY_MINUTES + 1)
            }
// 手动调度下一次（不调用 scheduleNext，因为我们要自定义延迟）
            val workManager = WorkManager.getInstance(applicationContext)
            val request = OneTimeWorkRequestBuilder<MessageWorker>()
                .setInputData(Data.Builder().putString("SESSION_ID", sessionId).build())
                .setInitialDelay(nextDelayMinutes, TimeUnit.MINUTES)
                .build()
            workManager.enqueueUniqueWork(
                "active_message_$sessionId",
                ExistingWorkPolicy.REPLACE,
                request
            )
            Log.d(TAG, "下次主动消息将在 ${nextDelayMinutes} 分钟后触发")

            scheduleNext(applicationContext, sessionId)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "后台任务失败", e)
            Result.success()
        }
    }
}