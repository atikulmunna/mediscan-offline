package com.mediscan.offline.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureFlowStateTest {
    @Test
    fun `upsertCapturedPanel replaces existing panel of same type`() {
        val existingPanels = listOf(
            CapturedPanel(
                localUri = "file://old-strip.jpg",
                panelType = CapturePanelType.Strip,
                panelName = "Strip",
            ),
            CapturedPanel(
                localUri = "file://date.jpg",
                panelType = CapturePanelType.PacketDateSide,
                panelName = "Date Side",
            ),
        )

        val updatedPanels = upsertCapturedPanel(
            existingPanels,
            CapturedPanel(
                localUri = "file://new-strip.jpg",
                panelType = CapturePanelType.Strip,
                panelName = "Strip",
            ),
        )

        assertEquals(2, updatedPanels.size)
        assertEquals("file://new-strip.jpg", updatedPanels.first { it.panelType == CapturePanelType.Strip }.localUri)
    }

    @Test
    fun `nextIncompleteStepIndex returns first missing required panel`() {
        val requiredOrder = listOf(
            CapturePanelType.PacketDateSide,
            CapturePanelType.PacketDetailSide,
            CapturePanelType.Strip,
        )
        val capturedPanels = listOf(
            CapturedPanel(
                localUri = "file://date.jpg",
                panelType = CapturePanelType.PacketDateSide,
                panelName = "Date Side",
            ),
        )

        val nextIndex = nextIncompleteStepIndex(
            requiredOrder = requiredOrder,
            panels = capturedPanels,
            currentIndex = 0,
        )

        assertEquals(1, nextIndex)
    }

    @Test
    fun `nextIncompleteStepIndex keeps current step when all required panels exist`() {
        val requiredOrder = listOf(
            CapturePanelType.PacketDateSide,
            CapturePanelType.PacketDetailSide,
            CapturePanelType.Strip,
        )
        val capturedPanels = listOf(
            CapturedPanel("file://date.jpg", CapturePanelType.PacketDateSide, "Date Side"),
            CapturedPanel("file://detail.jpg", CapturePanelType.PacketDetailSide, "Detail Side"),
            CapturedPanel("file://strip.jpg", CapturePanelType.Strip, "Strip"),
        )

        val nextIndex = nextIncompleteStepIndex(
            requiredOrder = requiredOrder,
            panels = capturedPanels,
            currentIndex = 2,
        )

        assertEquals(2, nextIndex)
    }
}
