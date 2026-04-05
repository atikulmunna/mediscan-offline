package com.mediscan.offline.data.local

import com.mediscan.offline.domain.CapturePanelType
import com.mediscan.offline.domain.CapturedPanel
import com.mediscan.offline.domain.MedicineDraft
import org.junit.Assert.assertEquals
import org.junit.Test

class MedicineMappersTest {
    @Test
    fun `toEntity maps editable draft and first image uri`() {
        val draft = MedicineDraft(
            brandName = "Naprosyn 500",
            genericName = "Naproxen USP 500 mg",
            manufacturer = "Radiant Pharmaceuticals Limited",
            batchNumber = "AB1234",
            strength = "500 mg",
            quantity = "10 tablets",
            manufactureDate = "01/2026",
            expiryDate = "01/2029",
            licenseNumber = "DG-77",
            activeIngredients = "Naproxen USP 500 mg",
            confidence = "medium",
        )
        val panels = listOf(
            CapturedPanel(
                localUri = "file://strip.jpg",
                panelType = CapturePanelType.Strip,
                panelName = "Strip",
            ),
        )

        val entity = draft.toEntity(panels)

        assertEquals("Naprosyn 500", entity.brandName)
        assertEquals("Naproxen USP 500 mg", entity.genericName)
        assertEquals("file://strip.jpg", entity.primaryImageUri)
    }

    @Test
    fun `toDraft and applyDraft support post save editing`() {
        val entity = MedicineEntity(
            id = 7,
            scannedAt = "2026-04-06T00:00:00Z",
            brandName = "Naprosyn 500",
            genericName = "Naproxen USP 500 mg",
            manufacturer = "Radiant Pharmaceuticals Limited",
            batchNumber = "AB1234",
            strength = "500 mg",
            quantity = "10 tablets",
            manufactureDate = "01/2026",
            expiryDate = "01/2029",
            licenseNumber = "DG-77",
            activeIngredients = "Naproxen USP 500 mg",
            confidence = "medium",
            primaryImageUri = "file://strip.jpg",
        )

        val editedDraft = entity.toDraft().copy(quantity = "20 tablets")
        val updatedEntity = entity.applyDraft(editedDraft)

        assertEquals("20 tablets", updatedEntity.quantity)
        assertEquals(7, updatedEntity.id)
        assertEquals("file://strip.jpg", updatedEntity.primaryImageUri)
    }
}
