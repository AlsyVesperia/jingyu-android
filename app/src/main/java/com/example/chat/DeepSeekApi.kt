package com.example.chat

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface DeepSeekApi {
    @POST("chat/completions")
    suspend fun sendMessage(@Body request: ChatRequest): Response<ChatResponse>
}

data class ThinkingConfig(
    val type: String
) {
    companion object {
        fun fromEnabled(enabled: Boolean): ThinkingConfig =
            ThinkingConfig(if (enabled) "enabled" else "disabled")
    }
}

data class ChatRequest(
    val model: String,
    val messages: List<Map<String, Any>>,
    val max_tokens: Int = 1024,
    val temperature: Double = 0.75,
    val top_p: Double = 0.85,
    // DeepSeek 当前 OpenAI 兼容接口使用 {"thinking":{"type":"enabled|disabled"}}。
    val thinking: ThinkingConfig? = null,
    val tools: List<Map<String, Any>>? = null,
    val tool_choice: String? = null
)

data class ChatResponse(
    val choices: List<Choice> = emptyList()
) {
    data class Choice(
        val message: MessageContent,
        val finish_reason: String? = null
    )

    data class MessageContent(
        val role: String = "assistant",
        val content: String? = null,
        val reasoning_content: String? = null,
        val tool_calls: List<ToolCall>? = null
    )

    data class ToolCall(
        val id: String,
        val type: String = "function",
        val function: FunctionCall
    )

    data class FunctionCall(
        val name: String,
        val arguments: String = "{}"
    )
}
