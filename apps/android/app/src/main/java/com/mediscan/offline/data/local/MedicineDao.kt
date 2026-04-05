package com.mediscan.offline.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface MedicineDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(medicine: MedicineEntity): Long

    @Update
    suspend fun update(medicine: MedicineEntity)

    @Query("SELECT * FROM medicines ORDER BY scannedAt DESC")
    suspend fun listAll(): List<MedicineEntity>

    @Query(
        """
        SELECT * FROM medicines
        WHERE (
            :query = ''
            OR lower(coalesce(brandName, '')) LIKE '%' || lower(:query) || '%'
            OR lower(coalesce(genericName, '')) LIKE '%' || lower(:query) || '%'
            OR lower(coalesce(manufacturer, '')) LIKE '%' || lower(:query) || '%'
        )
        AND (
            :confidence IS NULL
            OR lower(confidence) = lower(:confidence)
        )
        ORDER BY scannedAt DESC
        """,
    )
    suspend fun search(query: String, confidence: String?): List<MedicineEntity>

    @Query("SELECT * FROM medicines WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): MedicineEntity?
}
