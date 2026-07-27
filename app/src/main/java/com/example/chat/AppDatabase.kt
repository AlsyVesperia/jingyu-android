package com.example.chat

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow

class Converters {
    private val gson = Gson()

    @TypeConverter
    fun fromStringList(value: List<String>?): String? = value?.let { gson.toJson(it) }

    @TypeConverter
    fun toStringList(value: String?): List<String>? {
        if (value == null) return null
        val type = object : TypeToken<List<String>>() {}.type
        return gson.fromJson(value, type)
    }
}

@Entity(tableName = "chat_sessions")
data class ChatSession(
    @PrimaryKey var id: String = java.util.UUID.randomUUID().toString(),
    var name: String = "新对话",
    var avatarUrl: String = "",
    var backgroundUrl: String = "",
    var systemPrompt: String = "",
    var userName: String = "用户",
    var modelId: String = "deepseek-v4-flash",
    var apiKey: String = "",
    var apiUrl: String = "https://api.deepseek.com/",
    var temperature: Float = 0.75f,
    var topP: Float = 0.85f,
    var maxTokens: Int = 1024,
    var contextDays: Int = 30,
    var thinkingEnabled: Boolean = false,
    var searchEnabled: Boolean = false,
    var activeMessage: Boolean = true,
    var screenOnPerception: Boolean = false,
    var foregroundPerception: Boolean = false,
    var quietStartHour: Int = 0,
    var quietEndHour: Int = 0,
    var delayResponse: Boolean = true,
    var ignoreAllowed: Boolean = true,
    var activeVideoCall: Boolean = true,
    var rejectVideoCall: Boolean = true,
    var relation: String = "",
    var allowedNicknames: String = "",
    var bannedNicknames: String = "",
    var isPinned: Boolean = false,
    var lastActiveTime: Long = System.currentTimeMillis(),
    var bubbleColorUser: String = "",
    var bubbleColorAi: String = "",
    var aiNudgePhrase: String = "戳了戳小鲸鱼的尾巴",
    @Ignore
    var aiNudgePhraseList: List<String> = listOf(
        "戳了戳小鲸鱼的尾巴",
        "拍了拍小鲸鱼的脑袋",
        "轻轻捏了小鲸鱼的脸",
        "揉了揉小鲸鱼的肚子",
        "给小鲸鱼比了个心"
    ),
    var userNudgePhrase: String = "戳了戳你的脸",
    var isBlocked: Boolean = false,
    var isDeleted: Boolean = false,
    var avatarUri: String = "",
    var userAvatarUri: String = "",
    var supportsVision: Boolean = false,
    var visionTested: Boolean = false,
    var notifySound: Boolean = true,
    var notifyVibrate: Boolean = true,
    var callRingtone: String = "system",
    var quietNudge: Boolean = false,
    var mcpServerUrl: String = "",
    var mcpToolsJson: String = "",
)

@Entity(tableName = "chat_messages")
data class MessageEntity(
    @PrimaryKey var id: String = java.util.UUID.randomUUID().toString(),
    val sessionId: String,
    val text: String,
    val isUser: Boolean,
    val isSystem: Boolean = false,
    val thinking: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val parentUserMsgId: String = "",
    val versionNumber: Int = 1,
    val isActiveVersion: Boolean = true
)

// 回收站实体
@Entity(tableName = "trash_items")
data class TrashItem(
    @PrimaryKey var id: String = java.util.UUID.randomUUID().toString(),
    val originalId: String,
    val name: String,
    val type: String,
    val sessionId: String? = null,
    val deletedAt: Long = System.currentTimeMillis(),
    val originalData: String = ""
)

// 纪念日实体
@Entity(tableName = "anniversaries")
data class Anniversary(
    @PrimaryKey var id: String = java.util.UUID.randomUUID().toString(),
    val sessionId: String,
    val title: String,
    val date: Long, // 时间戳
    val type: String = "custom" // meeting, first_chat, custom
)

@Dao
interface ChatDao {
    // ========== 会话 ==========
    @Query("SELECT * FROM chat_sessions WHERE isDeleted = 0 ORDER BY isPinned DESC, lastActiveTime DESC")
    fun getAllSessions(): Flow<List<ChatSession>>

    @Query("SELECT * FROM chat_sessions WHERE id = :sessionId LIMIT 1")
    fun observeSession(sessionId: String): Flow<ChatSession?>

    @Query("SELECT * FROM chat_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getSession(sessionId: String): ChatSession?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ChatSession)

    @Update
    suspend fun updateSession(session: ChatSession)

    @Delete
    suspend fun deleteSession(session: ChatSession)

    // ========== 消息 ==========
    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesForSession(sessionId: String): Flow<List<MessageEntity>>

    @Insert
    suspend fun insertMessage(message: MessageEntity)

    @Delete
    suspend fun deleteMessage(message: MessageEntity)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun clearMessagesForSession(sessionId: String)

    @Query("DELETE FROM chat_messages WHERE timestamp < :cutoffTime")
    suspend fun deleteOldMessages(cutoffTime: Long)

    @Query("DELETE FROM chat_messages WHERE id = :messageId")
    suspend fun deleteMessageById(messageId: String)

    @Query(
        """
        UPDATE chat_messages
        SET text = :text,
            thinking = :thinking,
            timestamp = :timestamp,
            versionNumber = 1,
            isActiveVersion = 1
        WHERE id = :messageId
    """
    )
    suspend fun overwriteMessage(
        messageId: String,
        text: String,
        thinking: String,
        timestamp: Long
    )

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId AND timestamp >= :since ORDER BY timestamp ASC")
    suspend fun getMessagesSince(sessionId: String, since: Long): List<MessageEntity>

    // ========== 回收站 ==========
    @Query("SELECT * FROM trash_items ORDER BY deletedAt DESC")
    fun getAllTrashItems(): Flow<List<TrashItem>>

    @Insert
    suspend fun insertTrashItem(item: TrashItem)

    @Delete
    suspend fun deleteTrashItem(item: TrashItem)

    @Query("DELETE FROM trash_items WHERE deletedAt < :cutoffTime")
    suspend fun deleteExpiredTrashItems(cutoffTime: Long)

    // ========== 纪念日 ==========
    @Query("SELECT * FROM anniversaries WHERE sessionId = :sessionId")
    fun getAnniversaries(sessionId: String): Flow<List<Anniversary>>

    @Insert
    suspend fun insertAnniversary(anniversary: Anniversary)

    @Delete
    suspend fun deleteAnniversary(anniversary: Anniversary)

    @Query("UPDATE chat_messages SET isActiveVersion = :active WHERE id = :messageId")
    suspend fun updateMessageActiveVersion(messageId: String, active: Boolean)
}

@Database(
    entities = [ChatSession::class, MessageEntity::class, TrashItem::class, Anniversary::class],
    version = 13,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE chat_messages ADD COLUMN versionNumber INTEGER NOT NULL DEFAULT 1")
                database.execSQL("ALTER TABLE chat_messages ADD COLUMN isActiveVersion INTEGER NOT NULL DEFAULT 1")
            }
        }
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE chat_sessions ADD COLUMN mcpServerUrl TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE chat_sessions ADD COLUMN mcpToolsJson TEXT NOT NULL DEFAULT ''")
            }
        }
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE chat_sessions ADD COLUMN aiNudgePhrase TEXT NOT NULL DEFAULT '戳了戳小鲸鱼的尾巴'"
                )
            }
        }
    }

    val createdAt: Long = System.currentTimeMillis()  // 会话创建时间，导出导入时用
}
