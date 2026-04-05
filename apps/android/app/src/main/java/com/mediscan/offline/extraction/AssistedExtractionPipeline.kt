package com.mediscan.offline.extraction

import com.mediscan.offline.domain.CapturedPanel
import com.mediscan.offline.domain.ExtractionPipeline
import com.mediscan.offline.domain.ExtractionResult

class AssistedExtractionPipeline(
    private val basePipeline: ExtractionPipeline,
    private val draftAssist: LocalDraftAssist,
    private val shouldAssist: (ExtractionResult) -> Boolean = { result ->
        result.draft.confidence == "low"
    },
) : ExtractionPipeline {
    override suspend fun extract(panels: List<CapturedPanel>): ExtractionResult {
        val baseline = basePipeline.extract(panels)
        if (!shouldAssist(baseline)) {
            return baseline
        }

        val suggestion = draftAssist.refine(panels, baseline) ?: return baseline
        val mergedDraft = baseline.draft.copy(
            brandName = suggestion.draft.brandName ?: baseline.draft.brandName,
            genericName = suggestion.draft.genericName ?: baseline.draft.genericName,
            manufacturer = suggestion.draft.manufacturer ?: baseline.draft.manufacturer,
            strength = suggestion.draft.strength ?: baseline.draft.strength,
            activeIngredients = suggestion.draft.activeIngredients ?: baseline.draft.activeIngredients,
            confidence = suggestion.draft.confidence.ifBlank { baseline.draft.confidence },
        )

        return baseline.copy(
            draft = mergedDraft,
            reviewHints = baseline.reviewHints +
                suggestion.reviewHints +
                "Local assist applied: ${suggestion.providerLabel}. Verify the draft before saving.",
            fieldSources = baseline.fieldSources + suggestion.fieldSources,
        )
    }
}
