package com.mediscan.offline.extraction

import com.mediscan.offline.domain.CapturePanelType
import com.mediscan.offline.domain.CapturedPanel
import com.mediscan.offline.domain.ExtractionResult
import com.mediscan.offline.domain.MedicineDraft
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleGemmaResponseProviderTest {
    private val builder = GemmaAssistPromptBuilder()
    private val provider = SampleGemmaResponseProvider()

    @Test
    fun `generate returns structured sample response for known medicine text`() {
        val payload = builder.build(
            panels = listOf(
                CapturedPanel(
                    localUri = "file://strip.jpg",
                    panelType = CapturePanelType.Strip,
                    panelName = "Strip",
                    ocrText = "Naprosy\nন্যাপ্রোসিন ৫০০\nNaproxen USP 500 mg\nRadiant Pharmaceuticals Limited",
                ),
            ),
            baseline = ExtractionResult(
                draft = MedicineDraft(
                    brandName = null,
                    genericName = "Naproxen USP 500 mg",
                    confidence = "low",
                ),
            ),
        )

        val response = kotlinx.coroutines.runBlocking { provider.generate(payload) }

        requireNotNull(response)
        assertTrue(response.contains("\"brand_name\": \"Naprosyn 500\""))
        assertTrue(response.contains("\"generic_name\": \"Naproxen USP 500 mg\""))
        assertTrue(response.contains("\"manufacturer\": \"Radiant Pharmaceuticals Limited\""))
    }

    @Test
    fun `generate returns null when prompt has no known medicine clues`() {
        val payload = GemmaAssistPayload(
            systemPrompt = "stub",
            userPrompt = "Baseline draft:\n- confidence: low\nOCR panels:\n1. Strip\nocr_text:\nblurred unreadable text",
        )

        val response = kotlinx.coroutines.runBlocking { provider.generate(payload) }

        assertTrue(response == null)
    }
}
