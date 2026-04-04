package com.mediscan.offline.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "medicines")
data class MedicineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scannedAt: String,
    val brandName: String?,
    val genericName: String?,
    val manufacturer: String?,
    val batchNumber: String?,
    val strength: String?,
    val quantity: String?,
    val manufactureDate: String?,
    val expiryDate: String?,
    val licenseNumber: String?,
    val activeIngredients: String?,
    val confidence: String,
    val primaryImageUri: String?,
)
