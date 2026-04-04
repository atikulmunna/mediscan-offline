package com.mediscan.offline.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrStateTest {
    @Test
    fun `updatePanelOcrText only updates matching panel`() {
        val panels = listOf(
            CapturedPanel("file://date.jpg", CapturePanelType.PacketDateSide, "Date Side"),
            CapturedPanel("file://strip.jpg", CapturePanelType.Strip, "Strip"),
        )

        val updatedPanels = updatePanelOcrText(
            panels = panels,
            panelType = CapturePanelType.Strip,
            ocrText = "Paracetamol 500 mg",
        )

        assertEquals(null, updatedPanels[0].ocrText)
        assertEquals("Paracetamol 500 mg", updatedPanels[1].ocrText)
    }

    @Test
    fun `buildOcrProgressState reports running while work remains`() {
        val state = buildOcrProgressState(completedCount = 1, totalCount = 3)

        assertTrue(state.isRunning)
        assertEquals(1, state.completedCount)
        assertEquals(3, state.totalCount)
    }

    @Test
    fun `buildOcrProgressState stops running when error exists`() {
        val state = buildOcrProgressState(
            completedCount = 1,
            totalCount = 3,
            errorMessage = "OCR failed",
        )

        assertEquals(false, state.isRunning)
        assertEquals("OCR failed", state.errorMessage)
    }
}
