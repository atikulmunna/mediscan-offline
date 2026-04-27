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
            manufacturer = baseline.draft.manufacturer ?: suggestion.draft.manufacturer,
            strength = suggestion.draft.strength ?: baseline.draft.strength,
            activeIngredients = suggestion.draft.activeIngredients ?: baseline.draft.activeIngredients,
            confidence = suggestion.draft.confidence.ifBlank { baseline.draft.confidence },
        )

        val mergedFieldSources = buildMap {
            putAll(baseline.fieldSources)
            suggestion.fieldSources.forEach { (key, value) ->
                val shouldKeepBaseline = when (key) {
                    "brand_name" -> sameValue(baseline.draft.brandName, suggestion.draft.brandName)
                    "generic_name" -> sameValue(baseline.draft.genericName, suggestion.draft.genericName)
                    "strength" -> sameValue(baseline.draft.strength, suggestion.draft.strength)
                    "manufacturer" -> baseline.draft.manufacturer != null
                    else -> false
                }
                if (!shouldKeepBaseline) {
                    put(key, value)
                }
            }
        }

        val mergedReviewHints = (baseline.reviewHints + suggestion.reviewHints)
            .filterNot { hint ->
                (mergedDraft.brandName != null && hint.contains("Brand name missing", ignoreCase = true)) ||
                    (mergedDraft.genericName != null && hint.contains("Generic name missing", ignoreCase = true)) ||
                    (mergedDraft.batchNumber != null && hint.contains("Batch number missing", ignoreCase = true)) ||
                    (mergedDraft.expiryDate != null && hint.contains("Expiry date missing", ignoreCase = true)) ||
                    (mergedDraft.manufactureDate != null && hint.contains("Manufacture date missing", ignoreCase = true)) ||
                    (mergedDraft.strength != null && hint.contains("Strength missing", ignoreCase = true))
            }

        return baseline.copy(
            draft = mergedDraft,
            reviewHints = mergedReviewHints,
            fieldSources = mergedFieldSources,
            assistApplied = true,
            assistProvider = suggestion.providerLabel,
        )
    }
}

private fun sameValue(left: String?, right: String?): Boolean {
    if (left.isNullOrBlank() || right.isNullOrBlank()) {
        return false
    }
    return normalizeToken(left) == normalizeToken(right)
}
