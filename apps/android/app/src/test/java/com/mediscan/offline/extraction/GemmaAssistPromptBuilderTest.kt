package com.mediscan.offline.extraction

import com.mediscan.offline.domain.CapturePanelType
import com.mediscan.offline.domain.CapturedPanel
import com.mediscan.offline.domain.ExtractionResult
import com.mediscan.offline.domain.MedicineDraft
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaAssistPromptBuilderTest {
    private val builder = GemmaAssistPromptBuilder()

    @Test
    fun `build includes baseline draft and panel OCR evidence`() {
        val payload = builder.build(
            panels = listOf(
                CapturedPanel(
                    localUri = "file://strip.jpg",
                    panelType = CapturePanelType.Strip,
                    panelName = "Strip",
                    ocrText = "Naprosyn 500\nন্যাপ্রোসিন ৫০০\nNaproxen USP 500 mg",
                ),
            ),
            baseline = ExtractionResult(
                draft = MedicineDraft(
                    brandName = null,
                    genericName = "Naproxen USP 500 mg",
                    confidence = "low",
                ),
                reviewHints = listOf("Low extraction confidence"),
            ),
        )

        assertTrue(payload.systemPrompt.contains("Prefer English medicine names"))
        assertTrue(payload.systemPrompt.contains("Return JSON"))
        assertTrue(payload.userPrompt.contains("Baseline draft:"))
        assertTrue(payload.userPrompt.contains("Strip"))
        assertTrue(payload.userPrompt.contains("Naprosyn 500"))
        assertTrue(payload.userPrompt.contains("ন্যাপ্রোসিন"))
        assertTrue(payload.userPrompt.contains("Low extraction confidence"))
    }

    @Test
    fun `build includes guardrails for operational fields`() {
        val payload = builder.build(
            panels = emptyList(),
            baseline = ExtractionResult(
                draft = MedicineDraft(confidence = "low"),
            ),
        )

        assertTrue(payload.systemPrompt.contains("Do not hallucinate batch number"))
        assertTrue(payload.userPrompt.contains("Keep operational fields null unless clearly present."))
    }
}
