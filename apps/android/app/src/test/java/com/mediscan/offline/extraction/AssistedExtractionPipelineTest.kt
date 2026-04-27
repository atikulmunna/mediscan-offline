package com.mediscan.offline.extraction

import com.mediscan.offline.domain.CapturePanelType
import com.mediscan.offline.domain.CapturedPanel
import com.mediscan.offline.domain.ExtractionPipeline
import com.mediscan.offline.domain.ExtractionResult
import com.mediscan.offline.domain.MedicineDraft
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistedExtractionPipelineTest {
    @Test
    fun `returns baseline result when assist is not needed`() = runBlocking {
        val baseline = ExtractionResult(
            draft = MedicineDraft(
                brandName = "Naprosyn 500",
                genericName = "Naproxen USP 500 mg",
                confidence = "medium",
            ),
            reviewHints = listOf("Baseline hint"),
        )
        val pipeline = AssistedExtractionPipeline(
            basePipeline = FakePipeline(baseline),
            draftAssist = FakeAssist(
                ExtractionAssistSuggestion(
                    draft = baseline.draft.copy(brandName = "Wrong"),
                    providerLabel = "gemma-stub",
                ),
            ),
        )

        val result = pipeline.extract(emptyList())

        assertEquals("Naprosyn 500", result.draft.brandName)
        assertEquals(listOf("Baseline hint"), result.reviewHints)
        assertEquals(false, result.assistApplied)
    }

    @Test
    fun `merges assist suggestions into low confidence result`() = runBlocking {
        val baseline = ExtractionResult(
            draft = MedicineDraft(
                brandName = null,
                genericName = "Naproxen USP 500 mg",
                confidence = "low",
            ),
            reviewHints = listOf("Low extraction confidence"),
            fieldSources = mapOf("generic_name" to CapturePanelType.Strip.label),
        )
        val pipeline = AssistedExtractionPipeline(
            basePipeline = FakePipeline(baseline),
            draftAssist = FakeAssist(
                ExtractionAssistSuggestion(
                    draft = baseline.draft.copy(
                        brandName = "Naprosyn 500",
                        manufacturer = "Radiant Pharmaceuticals Limited",
                        confidence = "medium",
                    ),
                    reviewHints = listOf("Gemma assist recovered the brand name."),
                    fieldSources = mapOf("brand_name" to "Gemma Assist"),
                    providerLabel = "gemma-stub",
                ),
            ),
        )

        val result = pipeline.extract(
            listOf(
                CapturedPanel(
                    localUri = "file://strip.jpg",
                    panelType = CapturePanelType.Strip,
                    panelName = "Strip",
                ),
            ),
        )

        assertEquals("Naprosyn 500", result.draft.brandName)
        assertEquals("Radiant Pharmaceuticals Limited", result.draft.manufacturer)
        assertEquals("medium", result.draft.confidence)
        assertEquals("Gemma Assist", result.fieldSources["brand_name"])
        assertTrue(result.reviewHints.any { it.contains("Gemma assist recovered the brand name.") })
        assertEquals(true, result.assistApplied)
        assertEquals("gemma-stub", result.assistProvider)
    }

    @Test
    fun `keeps baseline manufacturer when assist suggests a weaker one`() = runBlocking {
        val baseline = ExtractionResult(
            draft = MedicineDraft(
                manufacturer = "Apex Pharma Limited",
                confidence = "low",
            ),
            fieldSources = mapOf("manufacturer" to CapturePanelType.PacketDetailSide.label),
        )
        val pipeline = AssistedExtractionPipeline(
            basePipeline = FakePipeline(baseline),
            draftAssist = FakeAssist(
                ExtractionAssistSuggestion(
                    draft = MedicineDraft(
                        manufacturer = "Square Pharmaceuticals Limited",
                        confidence = "medium",
                    ),
                    fieldSources = mapOf("manufacturer" to "Gemma Assist"),
                    providerLabel = "gemma-stub",
                ),
            ),
            shouldAssist = { true },
        )

        val result = pipeline.extract(emptyList())

        assertEquals("Apex Pharma Limited", result.draft.manufacturer)
        assertEquals(CapturePanelType.PacketDetailSide.label, result.fieldSources["manufacturer"])
        assertEquals(true, result.assistApplied)
    }

    @Test
    fun `keeps baseline field source and removes stale missing hints when assist matches value`() = runBlocking {
        val baseline = ExtractionResult(
            draft = MedicineDraft(
                brandName = "Alatrol",
                genericName = "Cetirizine Hydrochloride BP 5 mg",
                strength = "5 mg",
                confidence = "low",
            ),
            reviewHints = listOf(
                "Brand name missing: verify the strip or packet detail side.",
                "Generic name missing: review the strip text manually.",
                "Strength missing: verify the strip or detail side.",
            ),
            fieldSources = mapOf(
                "brand_name" to CapturePanelType.PacketDetailSide.label,
                "generic_name" to CapturePanelType.PacketDetailSide.label,
                "strength" to CapturePanelType.PacketDetailSide.label,
            ),
        )
        val pipeline = AssistedExtractionPipeline(
            basePipeline = FakePipeline(baseline),
            draftAssist = FakeAssist(
                ExtractionAssistSuggestion(
                    draft = MedicineDraft(
                        brandName = "Alatrol",
                        genericName = "Cetirizine Hydrochloride BP 5 mg",
                        strength = "5 mg",
                        confidence = "medium",
                    ),
                    reviewHints = listOf("Brand candidate was recovered from mixed OCR text."),
                    fieldSources = mapOf(
                        "brand_name" to "Gemma Assist",
                        "generic_name" to "Gemma Assist",
                        "strength" to "Gemma Assist",
                    ),
                    providerLabel = "gemma-stub",
                ),
            ),
            shouldAssist = { true },
        )

        val result = pipeline.extract(emptyList())

        assertEquals(CapturePanelType.PacketDetailSide.label, result.fieldSources["brand_name"])
        assertEquals(CapturePanelType.PacketDetailSide.label, result.fieldSources["generic_name"])
        assertEquals(CapturePanelType.PacketDetailSide.label, result.fieldSources["strength"])
        assertTrue(result.reviewHints.none { it.contains("Brand name missing") })
        assertTrue(result.reviewHints.none { it.contains("Generic name missing") })
        assertTrue(result.reviewHints.none { it.contains("Strength missing") })
    }

    private class FakePipeline(
        private val result: ExtractionResult,
    ) : ExtractionPipeline {
        override suspend fun extract(panels: List<CapturedPanel>): ExtractionResult = result
    }

    private class FakeAssist(
        private val suggestion: ExtractionAssistSuggestion?,
    ) : LocalDraftAssist {
        override suspend fun refine(
            panels: List<CapturedPanel>,
            baseline: ExtractionResult,
        ): ExtractionAssistSuggestion? = suggestion
    }
}
