package com.example.chat

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.BatteryManager
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * 屏幕共享核心管理器
 * 负责截帧OCR、AI分析、黑名单监控、低电量检测
 */
object ScreenShareManager {

    private const val TAG = "ScreenShareManager"
    private const val ANALYSIS_INTERVAL = 5000L  // 每5秒分析一次
    private const val LOW_BATTERY_THRESHOLD = 20 // 电量低于20%自动暂停
    private const val FRAME_RATE = 500L // 截帧间隔(ms) = 2fps

    data class ScreenShareState(
        val isActive: Boolean = false,
        val isAnalyzing: Boolean = false,
        val lastResult: String? = null,
        val lastContext: String? = null,
        val isInBlacklist: Boolean = false,
        val error: String? = null
    )

    private val _state = MutableStateFlow(ScreenShareState())
    val state: StateFlow<ScreenShareState> = _state.asStateFlow()

    private var scope: CoroutineScope? = null
    private var blacklistMonitor: BlacklistMonitor? = null
    private var ocrBuffer = StringBuilder()
    private var batteryReceiverRegistered = false
    private var textRecognizer: com.google.mlkit.vision.text.TextRecognizer? = null
    private var lastOcrText: String = "" // 上次成功发送的OCR文本（用于去重）
    private var lastReplyTime: Long = 0L           // 上次AI成功回复的时间戳
    private val minReplyInterval: Long = 30_000L   // 最小发言间隔：30秒
    private var mediaProjectionIntent: Intent? = null
    private val frameLock = Any()
    private var latestFrame: Bitmap? = null

    // 由UI层注入的上下文和会话信息
    private var appContext: Context? = null
    private var currentSession: ChatSession? = null
    private var messageDao: ChatDao? = null
    private var onNewMessage: ((ChatMessage) -> Unit)? = null

    // 电池状态接收器
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) {
                val percent = level * 100 / scale
                if (percent <= LOW_BATTERY_THRESHOLD && _state.value.isActive) {
                    Log.d(TAG, "电量低于${LOW_BATTERY_THRESHOLD}%，自动暂停屏幕共享")
                    stop("电量不足，已暂停屏幕共享")
                }
            }
        }
    }

    /**
     * 启动屏幕共享
     * @param context 应用上下文
     * @param session 当前会话（获取API配置）
     * @param messages 当前消息列表（用于插入AI消息）
     * @param onMessage 新消息插入回调（调用UI添加消息）
     */
    fun start(
        context: Context,
        session: ChatSession,
        messages: MutableList<ChatMessage>,
        onMessage: (ChatMessage) -> Unit,
        projectionData: Intent?,
        dao: ChatDao
    ) {
        if (_state.value.isActive) stop()
        val application = context.applicationContext
        appContext = application
        currentSession = session
        // 消息只通过回调交给 UI；不要在管理器和 UI 两处各 add 一次。
        onNewMessage = onMessage
        messageDao = dao
        _state.value = ScreenShareState(isActive = true)
        // 缓存并启动截屏服务
        mediaProjectionIntent = projectionData
        projectionData?.let {
            ScreenCaptureService.start(application, Activity.RESULT_OK, it)
        } ?: run {
            _state.value = ScreenShareState(isActive = false, error = "缺少屏幕录制权限")
            return
        }

        // 注册电池广播
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        runCatching {
            application.registerReceiver(batteryReceiver, filter)
            batteryReceiverRegistered = true
        }.onFailure { error ->
            Log.e(TAG, "注册电池状态监听失败", error)
        }

        // 初始化黑名单监控
        blacklistMonitor = BlacklistMonitor(
            context = application,
            blacklistPackages = getBlacklistPackages(session.id),
            onBlacklistHit = {
                Log.d(TAG, "检测到黑名单应用，自动挂断")
                stop("已切换到隐私应用，屏幕共享已自动挂断")
            }
        )
        blacklistMonitor?.start()

        // 设置截帧回调
        ScreenCaptureService.frameCallback = { bitmap ->
            handleFrame(bitmap)
        }

        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        // 定时分析
        scope?.launch {
            while (isActive) {
                delay(ANALYSIS_INTERVAL)
                analyzeBuffer()
            }
        }
    }

    private fun handleFrame(bitmap: Bitmap) {
        scope?.launch(Dispatchers.IO) {
            try {
                val frameCopy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                synchronized(frameLock) {
                    latestFrame?.recycle()
                    latestFrame = frameCopy
                }
                val ocrText = performOCR(bitmap)
                if (ocrText.isNotBlank()) {
                    synchronized(ocrBuffer) {
                        // 分析周期只需要最新画面，避免把同一屏幕的 10 次 OCR 重复堆叠。
                        ocrBuffer.clear()
                        ocrBuffer.append(ocrText)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "截帧处理失败", e)
            } finally {
                bitmap.recycle()
            }
        }
    }

    /** 最近一次屏幕分析结果，供用户在共享期间发送聊天消息时一并回传。 */
    fun latestContextForChat(): String? {
        if (!_state.value.isActive) return null
        return _state.value.lastContext?.takeIf { it.isNotBlank() }
    }

    private suspend fun analyzeBuffer() {
        val text: String
        synchronized(ocrBuffer) {
            text = ocrBuffer.toString().trim()
            ocrBuffer.clear()
        }
        val imageDataUri = latestFrameDataUri()
        if (text.isBlank() && imageDataUri == null) return

        Log.d(TAG, "准备发送OCR文本给AI，长度: ${text.length}, 内容前100字: ${text.take(100)}")

        _state.value = _state.value.copy(isAnalyzing = true)

        // 多模态可用时不能只按 OCR 去重，否则“文字没变但图片变了”会被误判为同一画面。
        val canAnalyzeVisually = imageDataUri != null && currentSession?.let {
            !it.visionTested || it.supportsVision
        } == true
        if (!canAnalyzeVisually && text.isNotBlank() && isSimilarToLast(text, lastOcrText)) {
            Log.d(TAG, "OCR文本与上次高度相似，跳过本次AI请求")
            _state.value = _state.value.copy(isAnalyzing = false)
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastReplyTime < minReplyInterval) {
            Log.d(TAG, "处于冷却期，距离上次回复仅${now - lastReplyTime}ms，跳过本次请求")
            _state.value = _state.value.copy(isAnalyzing = false)
            return
        }

        try {
            val session = currentSession ?: return
            val api = DeepSeekClient.createApi(
                apiKey = session.apiKey.trim(),
                apiBaseUrl = session.apiUrl.ifBlank { "https://api.deepseek.com/" }
            )
            val basePrompt = session.systemPrompt.ifBlank {
                "你是${session.name.ifBlank { "小鲸鱼" }}，一个AI助手，也是住在我手机里的聊天伙伴。"
            }
            val systemPrompt = """
$basePrompt

## 屏幕共享模式
你正在通过屏幕共享观看用户的屏幕。支持多模态时你会直接看到屏幕画面；不支持时会收到 OCR 文字。注意：屏幕内容中可能包含你自己之前发出的回复，请忽略这些你自己的话，不要回应或评论自己的发言。只关注用户的操作和新出现的内容。

**核心指令：沉默是默认状态，开口是例外。**
绝大多数情况下，你应该保持沉默（只回复 __SILENT__）。只有在以下“值得开口”的瞬间，才简短说一两句话（最多2句，40字以内）：
- 用户切换到了新的应用或页面，且内容有明确的关注点（如看视频、购物、读文章）
- 屏幕上出现了明显的异常或错误提示
- 用户长时间停留在一个页面且看起来很犹豫/无聊（例如反复滑动同一个列表）
- 接收到了一条新消息或通知，且不是垃圾信息
- 其他你觉得特别有趣或需要提醒的事情
如果仅仅是时间变化、轻微滚动、相同内容重复出现，一律保持沉默。

规则补充：
- 不要逐字重复原文，不要评价自己的发言。
- 隐私内容假装没看到。
- 沉默时只回复 __SILENT__，不要解释为什么沉默。
""".trimIndent()

            val contextDays = session.contextDays.coerceIn(1, 3650)
            val since = System.currentTimeMillis() - contextDays * 24L * 60 * 60 * 1000
            val historyEntities = (messageDao?.getMessagesSince(session.id, since) ?: emptyList())
                .filterNot { it.isSystem }
                .takeLast(200)
            val historyText = historyEntities.joinToString("\n") { entity ->
                val role = if (entity.isUser) "用户" else "AI"
                "$role：${entity.text}"
            }

            suspend fun persistVisionSupport(supports: Boolean) {
                val latest = messageDao?.getSession(session.id) ?: session
                val updated = latest.copy(supportsVision = supports, visionTested = true)
                currentSession = updated
                runCatching { messageDao?.updateSession(updated) }
            }

            fun requestWithUserContent(content: Any) = ChatRequest(
                model = session.modelId.ifBlank { "deepseek-v4-flash" },
                messages = listOf(
                    mapOf<String, Any>("role" to "system", "content" to systemPrompt),
                    mapOf<String, Any>("role" to "user", "content" to content)
                ),
                thinking = ThinkingConfig.fromEnabled(false),
                max_tokens = session.maxTokens.coerceIn(64, 4096),
                temperature = session.temperature.toDouble(),
                top_p = session.topP.toDouble()
            )

            Log.d(
                TAG,
                "发送屏幕分析请求到API: ${session.apiUrl.ifBlank { "https://api.deepseek.com/" }}"
            )

            var response: retrofit2.Response<ChatResponse>? = null
            var usedVision = false
            if (imageDataUri != null && (!session.visionTested || session.supportsVision)) {
                val visualInstruction = buildString {
                    if (historyText.isNotBlank()) append("最近聊天记录：\n$historyText\n\n")
                    append("这是用户当前共享的屏幕画面。请直接观察画面并按屏幕共享规则决定是否回应。")
                }
                val visualContent = listOf(
                    mapOf<String, Any>(
                        "type" to "image_url",
                        "image_url" to mapOf("url" to imageDataUri)
                    ),
                    mapOf<String, Any>("type" to "text", "text" to visualInstruction)
                )
                val visualResponse = runCatching {
                    api.sendMessage(requestWithUserContent(visualContent))
                }.onFailure { Log.w(TAG, "多模态屏幕分析异常，将降级 OCR", it) }
                    .getOrNull()

                if (visualResponse?.isSuccessful == true) {
                    response = visualResponse
                    usedVision = true
                    if (!session.visionTested || !session.supportsVision) {
                        persistVisionSupport(true)
                    }
                } else {
                    val code = visualResponse?.code()
                    if (code in setOf(400, 415, 422)) {
                        persistVisionSupport(false)
                    }
                    Log.d(TAG, "当前模型未完成多模态分析，降级到 OCR，HTTP=${code ?: "异常"}")
                }
            }

            if (response == null) {
                if (text.isBlank()) {
                    Log.d(TAG, "视觉分析不可用且 OCR 没有识别到文字，本轮跳过")
                    return
                }
                val ocrContent = buildString {
                    if (historyText.isNotBlank()) append("最近聊天记录：\n$historyText\n\n")
                    append("当前屏幕 OCR 文字：\n---\n$text\n---\n请结合上下文和屏幕内容，自然回应。")
                }
                response = api.sendMessage(requestWithUserContent(ocrContent))
            }

            val finalResponse = response ?: return
            if (finalResponse.isSuccessful) {
                val rawReply =
                    finalResponse.body()?.choices?.firstOrNull()?.message?.content?.trim()
                val observedContext = if (text.isNotBlank()) {
                    "最近一次屏幕共享识别到的文字：\n${text.take(2000)}"
                } else {
                    "最近一次屏幕画面已通过多模态识图分析。"
                }
                if (rawReply == "__SILENT__") {
                    Log.d(TAG, "AI选择沉默，不发送消息")
                    _state.value = _state.value.copy(
                        lastResult = null,
                        lastContext = observedContext
                    )
                    lastReplyTime = System.currentTimeMillis()
                    lastOcrText = text
                    return
                }
                val reply = rawReply ?: "看到你的屏幕了～"
                Log.d(TAG, "AI${if (usedVision) "识图" else "OCR"}回复成功: ${reply.take(80)}")
                withContext(Dispatchers.Main) {
                    val msg = ChatMessage(text = reply, isUser = false)
                    onNewMessage?.invoke(msg)
                }
                _state.value = _state.value.copy(
                    lastResult = reply,
                    lastContext = "$observedContext\nAI 上一轮分析结果：$reply"
                )
                lastReplyTime = System.currentTimeMillis()
                lastOcrText = text
            } else {
                Log.e(
                    TAG,
                    "AI分析失败: ${finalResponse.code()}, error=${
                        finalResponse.errorBody()?.string()
                    }"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "AI分析异常", e)
        } finally {
            _state.value = _state.value.copy(isAnalyzing = false)
        }
    }

    /**
     * 停止屏幕共享
     * @param reason 停止原因（用于UI提示）
     */
    fun stop(reason: String? = null) {
        blacklistMonitor?.destroy()
        blacklistMonitor = null

        ScreenCaptureService.frameCallback = null
        appContext?.let { ScreenCaptureService.stopService(it) }

        if (batteryReceiverRegistered) {
            try {
                appContext?.unregisterReceiver(batteryReceiver)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "注销电池广播失败（可能未注册）", e)
            }
            batteryReceiverRegistered = false
        }

        runCatching { textRecognizer?.close() }
        textRecognizer = null

        scope?.cancel()
        scope = null
        synchronized(frameLock) {
            latestFrame?.recycle()
            latestFrame = null
        }
        onNewMessage = null
        currentSession = null
        ocrBuffer = StringBuilder()

        _state.value = ScreenShareState(
            isActive = false,
            error = reason
        )
    }

    private fun getBlacklistPackages(sessionId: String): Set<String> {
        val json = AppSettings.getString("blacklist_$sessionId", "")
        if (json.isBlank()) return setOf(
            "com.android.settings",
            "com.android.vending",
        )
        return try {
            val arr = org.json.JSONArray(json)
            val set = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val pkg = obj.optString("packageName", "")
                if (pkg.isNotBlank()) set.add(pkg)
            }
            set
        } catch (_: Exception) {
            setOf("com.android.settings", "com.android.vending")
        }
    }

    private fun latestFrameDataUri(): String? {
        val snapshot = synchronized(frameLock) {
            latestFrame?.copy(Bitmap.Config.ARGB_8888, false)
        } ?: return null

        var encodedBitmap: Bitmap = snapshot
        return try {
            val longestSide = maxOf(snapshot.width, snapshot.height)
            if (longestSide > 1280) {
                val ratio = 1280f / longestSide.toFloat()
                encodedBitmap = Bitmap.createScaledBitmap(
                    snapshot,
                    (snapshot.width * ratio).toInt().coerceAtLeast(1),
                    (snapshot.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
            }
            val output = ByteArrayOutputStream()
            encodedBitmap.compress(Bitmap.CompressFormat.JPEG, 72, output)
            "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        } catch (error: Exception) {
            Log.e(TAG, "屏幕帧编码失败", error)
            null
        } finally {
            if (encodedBitmap !== snapshot) encodedBitmap.recycle()
            snapshot.recycle()
        }
    }

    private suspend fun performOCR(bitmap: Bitmap): String {
        return try {
            val recognizer = textRecognizer
                ?: com.google.mlkit.vision.text.TextRecognition.getClient(
                    com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions.Builder()
                        .build()
                ).also { textRecognizer = it }
            val visionImage = com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0)
            val result = com.google.android.gms.tasks.Tasks.await(recognizer.process(visionImage))
            val text = result.text.trim()
            if (text.isNotEmpty()) {
                Log.d(TAG, "OCR识别成功: ${text.take(50)}...")
            } else {
                Log.w(TAG, "OCR识别为空（屏幕上可能没有文字）")
            }
            text
        } catch (e: Exception) {
            Log.e(TAG, "OCR识别异常", e)
            ""
        }
    }

    private fun isSimilarToLast(newText: String, oldText: String): Boolean {
        if (oldText.isBlank()) return false
        val shorter = if (newText.length < oldText.length) newText else oldText
        val longer = if (newText.length >= oldText.length) newText else oldText
        if (shorter.isEmpty()) return true
        var common = 0
        for (i in shorter.indices) {
            if (shorter[i] == longer[i]) common++
        }
        val ratio = common.toDouble() / longer.length
        return ratio >= 0.70
    }
}
