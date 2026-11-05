package com.jarvis.assistant.androllm.chat.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Multi-conversation chat store for the Compose (AndroLLM) chat.
 *
 * A NEW, separate database file ([DATABASE_NAME]) — Jarvis' own databases and
 * its ChatRepository history are never touched. No destructive migration is
 * configured: future schema changes must ship explicit [androidx.room.migration.Migration]s
 * so user conversations are always preserved.
 */
@Database(
    entities = [ConversationEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AndroLlmChatDatabase : RoomDatabase() {

    abstract fun conversationDao(): ConversationDao

    abstract fun messageDao(): MessageDao

    companion object {
        const val DATABASE_NAME = "androllm_chat.db"

        @Volatile
        private var instance: AndroLlmChatDatabase? = null

        fun getInstance(context: Context): AndroLlmChatDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AndroLlmChatDatabase::class.java,
                    DATABASE_NAME
                ).build().also { instance = it }
            }
    }
}
