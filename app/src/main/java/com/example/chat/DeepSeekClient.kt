package com.example.chat

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object DeepSeekClient {
    fun createApi(apiKey: String, apiBaseUrl: String): DeepSeekApi {
        val cleanKey = apiKey.trim().replace("\n", "").replace("\r", "")  // 只删换行，不删空格
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Authorization", "Bearer $cleanKey")
                    .header("Content-Type", "application/json")
                    .build()
                chain.proceed(request)
            }
            .build()
        val baseUrl = if (apiBaseUrl.endsWith("/")) apiBaseUrl else "$apiBaseUrl/"
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(DeepSeekApi::class.java)
    }
}