package com.mediscan.offline.domain

data class MedicineDraft(
    val brandName: String? = null,
    val genericName: String? = null,
    val manufacturer: String? = null,
    val batchNumber: String? = null,
    val strength: String? = null,
    val quantity: String? = null,
    val manufactureDate: String? = null,
    val expiryDate: String? = null,
    val licenseNumber: String? = null,
    val activeIngredients: String? = null,
    val confidence: String = "unknown",
)
