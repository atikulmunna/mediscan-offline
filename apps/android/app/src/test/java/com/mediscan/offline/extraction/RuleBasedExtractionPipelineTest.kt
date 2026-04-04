package com.mediscan.offline.extraction

import com.mediscan.offline.domain.CapturePanelType
import com.mediscan.offline.domain.CapturedPanel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleBasedExtractionPipelineTest {
    private val pipeline = RuleBasedExtractionPipeline()

    @Test
    fun `extract normalizes noisy strip brand and generic`() = runBlocking {
        val panels = listOf(
            CapturedPanel(
                localUri = "file://strip.jpg",
                panelType = CapturePanelType.Strip,
                panelName = "Strip",
                ocrText = """
                    Naprosy
                    08204064 RAAEr
                    Naprosyn'soN
                    Naprosyn500
                    Naproxen USP 500 mg
                    Naprosyn500
                    Limited BSIC Tong, Gazipur, Bangladesh
                """.trimIndent(),
            ),
        )

        val result = pipeline.extract(panels)

        assertEquals("Naprosyn 500", result.draft.brandName)
        assertEquals("Naproxen USP 500 mg", result.draft.genericName)
        assertEquals("500 mg", result.draft.strength)
        assertEquals("Limited BSIC Tongi, Gazipur, Bangladesh", result.draft.manufacturer)
        assertEquals("Strip", result.fieldSources["brand_name"])
    }

    @Test
    fun `extract prefers packet date side for operational fields`() = runBlocking {
        val panels = listOf(
            CapturedPanel(
                localUri = "file://date.jpg",
                panelType = CapturePanelType.PacketDateSide,
                panelName = "Packet Date Side",
                ocrText = """
                    Batch: AB1234
                    MFG: 01/2026
                    EXP: 01/2029
                    MA No: DG-77
                """.trimIndent(),
            ),
            CapturedPanel(
                localUri = "file://detail.jpg",
                panelType = CapturePanelType.PacketDetailSide,
                panelName = "Packet Detail Side",
                ocrText = """
                    Naprosyn 500
                    Naproxen USP 500 mg
                    10 tablets
                """.trimIndent(),
            ),
        )

        val result = pipeline.extract(panels)

        assertEquals("AB1234", result.draft.batchNumber)
        assertEquals("01/2026", result.draft.manufactureDate)
        assertEquals("01/2029", result.draft.expiryDate)
        assertEquals("DG-77", result.draft.licenseNumber)
        assertEquals("10 tablets", result.draft.quantity)
        assertEquals("Packet Date Side", result.fieldSources["batch_number"])
    }

    @Test
    fun `extract emits review hints when packet date fields are missing`() = runBlocking {
        val panels = listOf(
            CapturedPanel(
                localUri = "file://strip.jpg",
                panelType = CapturePanelType.Strip,
                panelName = "Strip",
                ocrText = "Naprosyn 500\nNaproxen USP 500 mg",
            ),
        )

        val result = pipeline.extract(panels)

        assertTrue(result.reviewHints.any { it.contains("Batch number missing") })
        assertTrue(result.reviewHints.any { it.contains("Expiry date missing") })
    }
}
