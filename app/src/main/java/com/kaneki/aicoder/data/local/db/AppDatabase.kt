package com.kaneki.aicoder.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Version 2: tambah [ChatMessageEntity] (Bagian 7 blueprint).
 * Migrasi destruktif untuk versi awal (belum release publik) —
 * data project di Room hilang hanya jika schema mismatch; file extract di disk tetap.
 */
@Database(
    entities = [ProjectEntity::class, ChatMessageEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun projectDao(): ProjectDao
    abstract fun chatDao(): ChatDao

    companion object {
        private const val DB_NAME = "aicoder.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                    .fallbackToDestructiveMigration(true) // dropAllTables = true (sama dengan perilaku lama)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
