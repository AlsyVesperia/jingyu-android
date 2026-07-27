package com.example.chat

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import retrofit2.Response
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 一个轻量的远程 MCP（Streamable HTTP）客户端。
 *
 * 支持：
 * 1. 导入常见的 mcpServers JSON 配置；
 * 2. 手动填写远程 MCP URL；
 * 3. initialize -> notifications/initialized -> tools/list；
 * 4. 将 MCP inputSchema 转成 DeepSeek/OpenAI tools 格式；
 * 5. 执行 tools/call，并把结果交还给模型完成下一轮回复。
 *
 * Android 端不启动 stdio 子进程，因此仅支持远程 HTTP(S) MCP 服务器。
 */
object McpManager {
    private const val TAG = "McpManager"
    private const val PROTOCOL_VERSION = "2025-11-25"
    private const val MAX_TOOL_PAGES = 20

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val gson = Gson()
    private val requestIds = AtomicLong(1L)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private data class McpSession(
        val endpoint: String,
        val headers: Map<String, String>,
        val protocolVersion: String,
        val sessionId: String?
    )

    private data class RpcResponse(
        val body: JSONObject?,
        val sessionId: String?
    )

    data class ImportedConfig(
        val serverUrl: String = "",
        val headersJson: String = "",
        val toolsJson: String = "",
        val serverName: String = "",
        val warning: String = ""
    )

    data class DiscoveryResult(
        val tools: List<Map<String, Any>>,
        val normalizedToolsJson: String,
        val protocolVersion: String,
        val serverName: String = ""
    )

    class McpException(message: String, val httpCode: Int? = null) : Exception(message)

    private val sessionCache = ConcurrentHashMap<String, McpSession>()
    private val sessionLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * 兼容三种工具定义：
     * - MCP tools/list 中的 {name, description, inputSchema}
     * - 简化格式 {name, description, parameters}
     * - DeepSeek/OpenAI 格式 {type:"function", function:{...}}
     */
    fun parseTools(toolsJson: String): List<Map<String, Any>>? =
        runCatching { parseToolsOrThrow(toolsJson) }
            .onFailure { Log.e(TAG, "解析工具定义失败", it) }
            .getOrNull()

    fun parseToolsOrThrow(toolsJson: String): List<Map<String, Any>> {
        require(toolsJson.isNotBlank()) { "工具 JSON 为空" }
        val root = JSONTokener(toolsJson).nextValue()
        val array = extractToolsArray(root)
            ?: throw IllegalArgumentException("没有找到 tools 数组；支持直接数组、{tools:[...]} 或 {result:{tools:[...]}}")

        val result = mutableListOf<Map<String, Any>>()
        for (index in 0 until array.length()) {
            val rawTool = array.optJSONObject(index)
                ?: throw IllegalArgumentException("第 ${index + 1} 个工具不是 JSON 对象")

            val functionObject = rawTool.optJSONObject("function")
            val source = functionObject ?: rawTool
            val name = source.optString("name").trim()
            require(name.matches(Regex("[A-Za-z0-9_-]{1,64}"))) {
                "第 ${index + 1} 个工具 name 无效：只能包含字母、数字、下划线或短横线，且不超过 64 个字符"
            }

            val description = source.optString("description", "")
            val schemaValue = when {
                source.has("parameters") -> source.opt("parameters")
                source.has("inputSchema") -> source.opt("inputSchema")
                rawTool.has("inputSchema") -> rawTool.opt("inputSchema")
                else -> JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject())
                }
            }
            val schemaObject = coerceJsonObject(schemaValue, "工具 $name 的 parameters/inputSchema")

            result += mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to name,
                    "description" to description,
                    // 必须是对象，不能是 JSON 字符串；否则模型 API 会报 schema 格式错误。
                    "parameters" to jsonToKotlin(schemaObject)
                )
            )
        }
        return result
    }

    /** 将工具列表转成适合加密存储和再次读取的规范 JSON。 */
    fun normalizeToolsJson(tools: List<Map<String, Any>>): String = gson.toJson(tools)

    /**
     * 导入常见 MCP 配置文件。
     * 支持：
     * - {"mcpServers":{"name":{"url":"https://.../mcp","headers":{...}}}}
     * - {"url":"https://.../mcp","headers":{...}}
     * - 纯 tools 数组 / tools/list 响应
     */
    fun parseImportedConfig(rawJson: String): ImportedConfig {
        require(rawJson.isNotBlank()) { "文件内容为空" }
        val root = JSONTokener(rawJson).nextValue()

        val staticTools = runCatching {
            val tools = parseToolsOrThrow(rawJson)
            normalizeToolsJson(tools)
        }.getOrDefault("")

        if (root !is JSONObject) {
            return ImportedConfig(
                toolsJson = staticTools,
                warning = if (staticTools.isBlank()) "没有识别到 MCP 服务器 URL 或工具列表" else "已导入静态工具定义；仍需填写服务器 URL 才能实际执行工具"
            )
        }

        // 单服务器扁平格式，也兼容 {transport:{url,headers}} / {server:{...}}。
        val directConfig = root.optJSONObject("server") ?: root
        val directUrl = serverUrlFromConfig(directConfig)
        if (directUrl.isNotBlank()) {
            val warning = legacyTransportWarning(directConfig, directUrl)
            return ImportedConfig(
                serverUrl = directUrl,
                headersJson = gson.toJson(headersFromConfig(directConfig)),
                toolsJson = staticTools,
                serverName = firstNonBlank(directConfig.optString("name"), root.optString("name")),
                warning = warning
            )
        }

        // Claude Desktop / Cursor 等常见配置。
        val serversObject = root.optJSONObject("mcpServers")
            ?: root.optJSONObject("servers")
        if (serversObject != null) {
            val remoteServers = mutableListOf<ImportedConfig>()
            var sawStdioOnly = false
            val keys = serversObject.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                val rawConfig = serversObject.opt(name)
                val config = when (rawConfig) {
                    is JSONObject -> rawConfig
                    is String -> JSONObject().put("url", rawConfig)
                    else -> continue
                }
                val url = serverUrlFromConfig(config)
                if (url.isBlank()) {
                    val transportType =
                        config.optJSONObject("transport")?.optString("type").orEmpty()
                    if (config.has("command") || transportType.contains(
                            "stdio",
                            ignoreCase = true
                        )
                    ) {
                        sawStdioOnly = true
                    }
                    continue
                }
                remoteServers += ImportedConfig(
                    serverUrl = url,
                    headersJson = gson.toJson(headersFromConfig(config)),
                    toolsJson = staticTools,
                    serverName = name,
                    warning = legacyTransportWarning(config, url)
                )
            }

            if (remoteServers.isNotEmpty()) {
                val selected = remoteServers.first()
                val warnings = buildList {
                    if (remoteServers.size > 1) {
                        add("配置里有 ${remoteServers.size} 个远程服务器；当前版本先导入第一个：${selected.serverName}")
                    }
                    selected.warning.takeIf { it.isNotBlank() }?.let(::add)
                }
                return selected.copy(warning = warnings.joinToString("；"))
            }

            if (sawStdioOnly) {
                return ImportedConfig(
                    toolsJson = staticTools,
                    warning = "配置仅包含 stdio 服务器。Android 不能直接运行桌面命令，请把 MCP 部署为远程 HTTP(S) 服务后填写 URL"
                )
            }
        }

        return ImportedConfig(
            toolsJson = staticTools,
            warning = if (staticTools.isBlank()) "没有识别到远程 URL；请手动填写 MCP HTTP(S) 地址" else "已读取工具定义；请再填写 MCP HTTP(S) 地址"
        )
    }

    /** 连接远程服务器并自动读取 tools/list。 */
    suspend fun discoverTools(
        serverUrl: String,
        headersJson: String = ""
    ): DiscoveryResult = withContext(Dispatchers.IO) {
        val endpoint = normalizeEndpoint(serverUrl)
        val headers = parseHeaders(headersJson)
        val session = getOrCreateSession(endpoint, headers, forceRefresh = true)

        val allTools = mutableListOf<JSONObject>()
        var cursor: String? = null
        var pages = 0
        do {
            pages++
            if (pages > MAX_TOOL_PAGES) throw McpException("工具分页超过 $MAX_TOOL_PAGES 页，已停止读取")

            val params = JSONObject()
            cursor?.takeIf { it.isNotBlank() }?.let { params.put("cursor", it) }
            val body = rpcRequest("tools/list", params)
            val rpc = postJson(session, body)
            val result = rpc.body?.optJSONObject("result")
                ?: throw McpException("tools/list 响应缺少 result")
            val tools = result.optJSONArray("tools") ?: JSONArray()
            for (i in 0 until tools.length()) {
                tools.optJSONObject(i)?.let { allTools += it }
            }
            cursor = result.optString("nextCursor").takeIf { it.isNotBlank() }
        } while (cursor != null)

        val mcpArray = JSONArray()
        allTools.forEach { mcpArray.put(it) }
        val deepSeekTools = parseToolsOrThrow(mcpArray.toString())
        DiscoveryResult(
            tools = deepSeekTools,
            normalizedToolsJson = normalizeToolsJson(deepSeekTools),
            protocolVersion = session.protocolVersion,
            serverName = session.endpoint
        )
    }

    /**
     * 完整执行 DeepSeek tool_calls 循环。
     * 模型返回工具调用 -> MCP tools/call -> 把 tool 结果加入消息 -> 再请求模型。
     */
    suspend fun sendWithTools(
        api: DeepSeekApi,
        initialRequest: ChatRequest,
        serverUrl: String,
        headersJson: String = "",
        maxRounds: Int = 4,
        onProgress: (suspend (String) -> Unit)? = null
    ): Response<ChatResponse> {
        if (initialRequest.tools.isNullOrEmpty()) {
            return api.sendMessage(initialRequest)
        }

        val messages = initialRequest.messages.toMutableList()
        repeat(maxRounds.coerceAtLeast(1)) {
            val response = api.sendMessage(initialRequest.copy(messages = messages))
            if (!response.isSuccessful) return response

            val assistant = response.body()?.choices?.firstOrNull()?.message ?: return response
            val toolCalls = assistant.tool_calls.orEmpty()
            if (toolCalls.isEmpty()) return response

            val assistantMessage = mapOf(
                "role" to "assistant",
                "content" to (assistant.content ?: ""),
                "tool_calls" to toolCalls.map { call ->
                    mapOf(
                        "id" to call.id,
                        "type" to "function",
                        "function" to mapOf(
                            "name" to call.function.name,
                            "arguments" to call.function.arguments
                        )
                    )
                }
            )
            messages += assistantMessage

            for (toolCall in toolCalls) {
                onProgress?.invoke("🔧 正在调用 MCP：${toolCall.function.name}")
                var executionFailed = false
                val execution = if (serverUrl.isBlank()) {
                    executionFailed = true
                    ToolExecutionResult("MCP 工具执行失败：没有配置服务器 URL", "{}", "{}")
                } else {
                    runCatching {
                        callTool(
                            serverUrl,
                            headersJson,
                            toolCall.function.name,
                            toolCall.function.arguments
                        )
                    }.getOrElse { error ->
                        executionFailed = true
                        ToolExecutionResult(
                            "MCP 工具执行失败：${error.message?.take(200) ?: "未知错误"}",
                            "{}",
                            "{}"
                        )
                    }
                }
                val result = execution.content
                onProgress?.invoke(
                    if (executionFailed) "⚠️ MCP 调用失败：${toolCall.function.name}"
                    else "✅ MCP 已返回：${toolCall.function.name}"
                )
                messages += mapOf(
                    "role" to "tool",
                    "tool_call_id" to toolCall.id,
                    "content" to result
                )
            }
        }

        return api.sendMessage(
            initialRequest.copy(
                messages = messages,
                tools = null,
                tool_choice = null
            )
        )
    }

    suspend fun callTool(
        serverUrl: String,
        headersJson: String,
        functionName: String,
        arguments: String
    ): ToolExecutionResult = withContext(Dispatchers.IO) {  // 注意：返回类型变了！
        val endpoint = normalizeEndpoint(serverUrl)
        val headers = parseHeaders(headersJson)
        val session = getOrCreateSession(endpoint, headers)

        val argumentObject = try {
            if (arguments.isBlank()) JSONObject() else JSONObject(arguments)
        } catch (e: Exception) {
            throw McpException("工具参数不是有效 JSON 对象：${e.message}")
        }

        val params = JSONObject().apply {
            put("name", functionName)
            put("arguments", argumentObject)
        }

        // 关键改动1：在这里把“请求体”完整抓下来！
        val rpcBody = rpcRequest("tools/call", params)
        val requestJson = rpcBody.toString()

        val rpc = postJson(session, rpcBody)
        val result = rpc.body?.optJSONObject("result")
            ?: throw McpException("tools/call 响应缺少 result")

        // 关键改动2：在这里把“返回体”完整抓下来！
        val responseJson = rpc.body?.toString() ?: "{}"

        val content = normalizeToolResult(result)

        // 关键改动3：把抓到的数据塞进新类返回
        return@withContext ToolExecutionResult(content, requestJson, responseJson)
    }

    fun clearCachedSession(serverUrl: String, headersJson: String = "") {
        runCatching {
            val endpoint = normalizeEndpoint(serverUrl)
            val headers = parseHeaders(headersJson)
            sessionCache.remove(cacheKey(endpoint, headers))
        }
    }

    private suspend fun getOrCreateSession(
        endpoint: String,
        headers: Map<String, String>,
        forceRefresh: Boolean = false
    ): McpSession {
        val key = cacheKey(endpoint, headers)
        if (!forceRefresh) sessionCache[key]?.let { return it }
        val mutex = sessionLocks.getOrPut(key) { Mutex() }
        return mutex.withLock {
            if (!forceRefresh) sessionCache[key]?.let { return@withLock it }
            val created = initialize(endpoint, headers)
            sessionCache[key] = created
            created
        }
    }

    private fun initialize(endpoint: String, headers: Map<String, String>): McpSession {
        val initializeParams = JSONObject().apply {
            put("protocolVersion", PROTOCOL_VERSION)
            put("capabilities", JSONObject())
            put("clientInfo", JSONObject().apply {
                put("name", "XiaoJingYu-Android")
                put("title", "小鲸鱼 Android")
                put("version", "1.0")
            })
        }
        val initialRpc = postJsonRaw(
            endpoint = endpoint,
            headers = headers,
            body = rpcRequest("initialize", initializeParams),
            protocolVersion = null,
            sessionId = null
        )
        val result = initialRpc.body?.optJSONObject("result")
            ?: throw McpException("initialize 响应缺少 result")
        val negotiatedVersion = result.optString("protocolVersion", PROTOCOL_VERSION)

        val session = McpSession(
            endpoint = endpoint,
            headers = headers,
            protocolVersion = negotiatedVersion,
            sessionId = initialRpc.sessionId
        )

        // 通知服务器初始化已完成。该请求通常返回 202 且没有正文。
        postJson(
            session,
            JSONObject().apply {
                put("jsonrpc", "2.0")
                put("method", "notifications/initialized")
            },
            allowEmptyBody = true
        )
        return session
    }

    private fun postJson(
        session: McpSession,
        body: JSONObject,
        allowEmptyBody: Boolean = false
    ): RpcResponse = postJsonRaw(
        endpoint = session.endpoint,
        headers = session.headers,
        body = body,
        protocolVersion = session.protocolVersion,
        sessionId = session.sessionId,
        allowEmptyBody = allowEmptyBody
    )

    private fun postJsonRaw(
        endpoint: String,
        headers: Map<String, String>,
        body: JSONObject,
        protocolVersion: String?,
        sessionId: String?,
        allowEmptyBody: Boolean = false
    ): RpcResponse {
        val requestBuilder = Request.Builder()
            .url(endpoint)
            .post(body.toString().toRequestBody(jsonMediaType))

        headers.forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank()) requestBuilder.header(name, value)
        }
        requestBuilder.header("Content-Type", "application/json")
        requestBuilder.header("Accept", "application/json, text/event-stream")
        protocolVersion?.let { requestBuilder.header("MCP-Protocol-Version", it) }
        sessionId?.let { requestBuilder.header("Mcp-Session-Id", it) }

        httpClient.newCall(requestBuilder.build()).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            val returnedSessionId = response.header("Mcp-Session-Id") ?: sessionId
            if (!response.isSuccessful) {
                val details = responseText.take(1000).ifBlank { response.message }
                throw McpException("MCP HTTP ${response.code}: $details", response.code)
            }
            if (responseText.isBlank()) {
                if (allowEmptyBody || response.code == 202) return RpcResponse(
                    null,
                    returnedSessionId
                )
                throw McpException("MCP 服务器返回了空响应")
            }

            val expectedId = body.opt("id")
                .takeUnless { it == null || it == JSONObject.NULL }
                ?.toString()
            val json = parseJsonOrSse(responseText, expectedId)
            json.optJSONObject("error")?.let { error ->
                val message = error.optString("message", error.toString())
                throw McpException("MCP JSON-RPC 错误：$message")
            }
            return RpcResponse(json, returnedSessionId)
        }
    }

    private fun rpcRequest(method: String, params: JSONObject = JSONObject()): JSONObject =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", requestIds.getAndIncrement())
            put("method", method)
            put("params", params)
        }

    private fun parseJsonOrSse(raw: String, expectedId: String?): JSONObject {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) return JSONObject(trimmed)

        val payloads = mutableListOf<JSONObject>()
        val current = StringBuilder()

        fun flush() {
            if (current.isEmpty()) return
            runCatching { JSONObject(current.toString()) }
                .getOrNull()
                ?.let { payloads += it }
            current.clear()
        }

        raw.lineSequence().forEach { line ->
            when {
                line.isBlank() -> flush()
                line.startsWith("data:") -> {
                    if (current.isNotEmpty()) current.append('\n')
                    current.append(line.removePrefix("data:").trimStart())
                }
            }
        }
        flush()

        if (payloads.isEmpty()) {
            throw McpException("无法解析 MCP 响应（既不是 JSON，也不是有效 SSE data）")
        }

        if (expectedId != null) {
            payloads.firstOrNull { payload ->
                payload.opt("id")
                    .takeUnless { it == null || it == JSONObject.NULL }
                    ?.toString() == expectedId
            }?.let { return it }
        }

        // 某些服务器会先发送 notification，再发送真正的 JSON-RPC response。
        // 没有匹配到 id 时，优先返回含 result/error 的对象，而不是盲目取最后一条事件。
        return payloads.firstOrNull { it.has("result") || it.has("error") }
            ?: payloads.last()
    }

    private fun normalizeToolResult(result: JSONObject): String {
        val pieces = mutableListOf<String>()
        val content = result.optJSONArray("content")
        if (content != null) {
            for (i in 0 until content.length()) {
                val item = content.optJSONObject(i) ?: continue
                when (item.optString("type")) {
                    "text" -> item.optString("text").takeIf { it.isNotBlank() }
                        ?.let { pieces += it }

                    "resource" -> {
                        val resource = item.optJSONObject("resource")
                        val text = resource?.optString("text").orEmpty()
                        val uri = resource?.optString("uri").orEmpty()
                        pieces += when {
                            text.isNotBlank() -> text
                            uri.isNotBlank() -> "资源：$uri"
                            else -> item.toString()
                        }
                    }

                    "image" -> pieces += "[MCP 返回了一张图片]"
                    "audio" -> pieces += "[MCP 返回了一段音频]"
                    else -> pieces += item.toString()
                }
            }
        }
        result.opt("structuredContent")?.takeUnless { it == JSONObject.NULL }?.let {
            pieces += "structuredContent: ${jsonValueToCompactString(it)}"
        }
        if (pieces.isEmpty()) pieces += result.toString()
        val prefix = if (result.optBoolean("isError", false)) "MCP 工具报告错误：" else ""
        return prefix + pieces.joinToString("\n")
    }

    private fun extractToolsArray(root: Any?): JSONArray? = when (root) {
        is JSONArray -> root
        is JSONObject -> when {
            root.optJSONArray("tools") != null -> root.optJSONArray("tools")
            root.optJSONObject("result")
                ?.optJSONArray("tools") != null -> root.optJSONObject("result")
                ?.optJSONArray("tools")

            else -> null
        }

        else -> null
    }

    private fun coerceJsonObject(value: Any?, label: String): JSONObject = when (value) {
        null, JSONObject.NULL -> JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject())
        }

        is JSONObject -> value
        is String -> runCatching { JSONObject(value) }
            .getOrElse { throw IllegalArgumentException("$label 不是有效 JSON 对象") }

        else -> throw IllegalArgumentException("$label 必须是 JSON 对象")
    }

    @Suppress("UNCHECKED_CAST")
    private fun jsonToKotlin(value: Any?): Any = when (value) {
        null, JSONObject.NULL -> emptyMap<String, Any>()
        is JSONObject -> buildMap<String, Any> {
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, jsonToKotlin(value.get(key)))
            }
        }

        is JSONArray -> buildList<Any> {
            for (i in 0 until value.length()) add(jsonToKotlin(value.get(i)))
        }

        is Boolean, is Int, is Long, is Double, is String -> value
        is Number -> value.toDouble()
        else -> value.toString()
    }

    private fun parseHeaders(headersJson: String): Map<String, String> {
        if (headersJson.isBlank()) return emptyMap()
        val objectValue = runCatching { JSONObject(headersJson) }
            .getOrElse { throw McpException("headers 不是有效 JSON 对象：${it.message}") }
        return jsonObjectToStringMap(objectValue)
    }

    private fun jsonObjectToStringMap(value: JSONObject?): Map<String, String> {
        if (value == null) return emptyMap()
        val result = linkedMapOf<String, String>()
        val keys = value.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val headerValue = value.opt(key)
            if (headerValue != null && headerValue != JSONObject.NULL) {
                result[key] = headerValue.toString()
            }
        }
        return result
    }

    private fun serverUrlFromConfig(config: JSONObject): String {
        val transport = config.optJSONObject("transport")
        return firstNonBlank(
            config.optString("url"),
            config.optString("serverUrl"),
            config.optString("endpoint"),
            transport?.optString("url").orEmpty(),
            transport?.optString("serverUrl").orEmpty(),
            transport?.optString("endpoint").orEmpty()
        )
    }

    private fun headersFromConfig(config: JSONObject): Map<String, String> {
        val direct = config.optJSONObject("headers")
        val nested = config.optJSONObject("transport")?.optJSONObject("headers")
        return jsonObjectToStringMap(direct ?: nested)
    }

    private fun legacyTransportWarning(config: JSONObject, url: String): String {
        val transportType = firstNonBlank(
            config.optString("type"),
            config.optJSONObject("transport")?.optString("type").orEmpty()
        )
        val looksLegacySse = transportType.contains("sse", ignoreCase = true) ||
                runCatching {
                    URI(url).path.orEmpty().trimEnd('/').endsWith("/sse", ignoreCase = true)
                }
                    .getOrDefault(false)
        return if (looksLegacySse) {
            "检测到可能是旧版 HTTP+SSE 地址；当前 Android 客户端仅直接执行 Streamable HTTP，请优先填写服务器的 /mcp 端点"
        } else {
            ""
        }
    }

    private fun normalizeEndpoint(raw: String): String {
        val endpoint = raw.trim()
        require(endpoint.isNotBlank()) { "MCP 服务器 URL 为空" }
        val uri = runCatching { URI(endpoint) }
            .getOrElse { throw McpException("URL 格式错误：${it.message}") }
        require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
            "Android 版仅支持 http:// 或 https:// 远程 MCP 地址"
        }
        require(!uri.host.isNullOrBlank()) { "URL 缺少主机名" }
        return endpoint
    }

    private fun cacheKey(endpoint: String, headers: Map<String, String>): String =
        endpoint + "\n" + headers.toSortedMap().entries.joinToString("\n") { "${it.key}:${it.value}" }

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() }.orEmpty()

    private fun jsonValueToCompactString(value: Any): String = when (value) {
        is JSONObject, is JSONArray -> value.toString()
        else -> value.toString()
    }

    /**
     * 释放所有缓存资源。当 App 退出或用户清空配置时调用。
     */
    fun release() {
        sessionCache.clear()
        sessionLocks.clear()
        // 可选：关闭 httpClient 的连接池
        runCatching {
            httpClient.dispatcher.executorService.shutdown()
            httpClient.connectionPool.evictAll()
        }
    }

    data class ToolExecutionResult(
        val content: String,          // 原本返回给AI的文字内容
        val requestJson: String,      // 新增：发给MCP的完整请求参数
        val responseJson: String      // 新增：MCP返回的完整原始JSON
    )
}
