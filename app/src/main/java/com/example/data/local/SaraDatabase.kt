package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        PersonalTaskEntity::class,
        MemoryNoteEntity::class,
        CommandLogEntity::class,
        RoutineEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class SaraDatabase : RoomDatabase() {
    abstract fun saraDao(): SaraDao

    companion object {
        @Volatile
        private var INSTANCE: SaraDatabase? = null

        fun getInstance(context: Context): SaraDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SaraDatabase::class.java,
                    "sara_assistant.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
