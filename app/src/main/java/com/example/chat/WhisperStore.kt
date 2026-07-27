package com.example.chat

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

data class Whisper(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

object WhisperStore {
    private lateinit var prefs: SharedPreferences
    private val gson = Gson()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("whisper_prefs", Context.MODE_PRIVATE)
    }

    @Synchronized
    fun getAll(): List<Whisper> {
        val json = prefs.getString("whispers", "[]") ?: "[]"
        val type = object : TypeToken<List<Whisper>>() {}.type
        return gson.fromJson(json, type)
    }

    @Synchronized
    fun add(whisper: Whisper) {
        val list = getAll().toMutableList()
        list.add(0, whisper)
        prefs.edit()
            .putString("whispers", gson.toJson(if (list.size > 100) list.subList(0, 100) else list))
            .apply()
    }

    fun getLastGeneratedDate() = prefs.getString("last_generated_date", "") ?: ""

    fun setLastGeneratedDate(date: String) =
        prefs.edit().putString("last_generated_date", date).apply()
}