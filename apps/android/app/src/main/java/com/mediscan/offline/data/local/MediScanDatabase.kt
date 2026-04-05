package com.mediscan.offline.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [MedicineEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class MediScanDatabase : RoomDatabase() {
    abstract fun medicineDao(): MedicineDao

    companion object {
        @Volatile
        private var instance: MediScanDatabase? = null

        fun getInstance(context: Context): MediScanDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MediScanDatabase::class.java,
                    "mediscan_offline.db",
                ).build().also { database ->
                    instance = database
                }
            }
        }
    }
}
