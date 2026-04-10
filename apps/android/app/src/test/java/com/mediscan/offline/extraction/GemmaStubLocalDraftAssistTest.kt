package com.mediscan.offline.extraction

import com.mediscan.offline.domain.CapturePanelType
import com.mediscan.offline.domain.CapturedPanel
import com.mediscan.offline.domain.ExtractionResult
import com.mediscan.offline.domain.MedicineDraft
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GemmaStubLocalDraftAssistTest {
    @Test
    fun `returns null when no response provider output exists`() = runBlocking {
        val assist = GemmaStubLocalDraftAssist(
            responseProvider = { null },
        )

        val result = assist.refine(emptyList(), ExtractionResult(draft = MedicineDraft(confidence = "low")))

        assertNull(result)
    }

    @Test
    fun `parses provider response into assist suggestion`() = runBlocking {
        val assist = GemmaStubLocalDraftAssist(
            responseProvider = { payload ->
                if (payload.userPrompt.contains("Naprosyn")) {
                    """
                    {
                      "brand_name": "Naprosyn 500",
                      "generic_name": "Naproxen USP 500 mg",
                      "manufacturer": "Radiant Pharmaceuticals Limited",
                      "strength": "500 mg",
                      "active_ingredients": "Naproxen USP 500 mg",
                      "confidence": "medium",
                      "review_hints": ["Recovered brand from bilingual OCR"]
                    }
                    """.trimIndent()
                } else {
                    null
                }
            },
        )

        val result = assist.refine(
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
            ),
        )

        assertNotNull(result)
        assertEquals("Naprosyn 500", result?.draft?.brandName)
        assertEquals("medium", result?.draft?.confidence)
        assertEquals("Gemma Assist", result?.fieldSources?.get("brand_name"))
    }
}
