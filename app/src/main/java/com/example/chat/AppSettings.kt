package com.example.chat

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

object AppSettings {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        try {
            val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            prefs = EncryptedSharedPreferences.create(
                "xiao_jing_yu_secret_prefs",
                masterKey,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // 加密存储创建失败，降级到普通 SharedPreferences（至少不会闪退）
            android.util.Log.e("AppSettings", "加密存储创建失败，降级到普通存储", e)
            prefs =
                context.getSharedPreferences("xiao_jing_yu_fallback_prefs", Context.MODE_PRIVATE)
        }
    }

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(value) = prefs.edit().putString("api_key", value).apply()

    var apiBaseUrl: String
        get() = prefs.getString("api_base_url", "https://api.deepseek.com/")
            ?: "https://api.deepseek.com/"
        set(value) = prefs.edit().putString("api_base_url", value).apply()

    var usageReminderHours: Float
        get() = prefs.getFloat("usage_reminder_hours", 0f)
        set(value) = prefs.edit().putFloat("usage_reminder_hours", value).apply()

    fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)
    fun putLong(key: String, value: Long) = prefs.edit().putLong(key, value).apply()
    fun getString(key: String, default: String): String = prefs.getString(key, default) ?: default
    fun putString(key: String, value: String?) = prefs.edit().putString(key, value).apply()
    fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
    fun putInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
}