package com.mediscan.offline.data.local

import com.mediscan.offline.domain.CapturedPanel
import com.mediscan.offline.domain.MedicineDraft
import java.time.Instant

fun MedicineDraft.toEntity(capturedPanels: List<CapturedPanel>): MedicineEntity {
    return MedicineEntity(
        scannedAt = Instant.now().toString(),
        brandName = brandName,
        genericName = genericName,
        manufacturer = manufacturer,
        batchNumber = batchNumber,
        strength = strength,
        quantity = quantity,
        manufactureDate = manufactureDate,
        expiryDate = expiryDate,
        licenseNumber = licenseNumber,
        activeIngredients = activeIngredients,
        confidence = confidence,
        primaryImageUri = capturedPanels.firstOrNull()?.localUri,
    )
}

fun MedicineEntity.toDraft(): MedicineDraft {
    return MedicineDraft(
        brandName = brandName,
        genericName = genericName,
        manufacturer = manufacturer,
        batchNumber = batchNumber,
        strength = strength,
        quantity = quantity,
        manufactureDate = manufactureDate,
        expiryDate = expiryDate,
        licenseNumber = licenseNumber,
        activeIngredients = activeIngredients,
        confidence = confidence,
    )
}

fun MedicineEntity.applyDraft(draft: MedicineDraft): MedicineEntity {
    return copy(
        brandName = draft.brandName,
        genericName = draft.genericName,
        manufacturer = draft.manufacturer,
        batchNumber = draft.batchNumber,
        strength = draft.strength,
        quantity = draft.quantity,
        manufactureDate = draft.manufactureDate,
        expiryDate = draft.expiryDate,
        licenseNumber = draft.licenseNumber,
        activeIngredients = draft.activeIngredients,
        confidence = draft.confidence,
    )
}
