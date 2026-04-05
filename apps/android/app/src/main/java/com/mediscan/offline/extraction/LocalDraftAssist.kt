package com.mediscan.offline.extraction

import com.mediscan.offline.domain.CapturedPanel
import com.mediscan.offline.domain.ExtractionResult
import com.mediscan.offline.domain.MedicineDraft

data class ExtractionAssistSuggestion(
    val draft: MedicineDraft,
    val reviewHints: List<String> = emptyList(),
    val fieldSources: Map<String, String> = emptyMap(),
    val providerLabel: String = "local-assist",
)

interface LocalDraftAssist {
    suspend fun refine(
        panels: List<CapturedPanel>,
        baseline: ExtractionResult,
    ): ExtractionAssistSuggestion?
}

class NoOpLocalDraftAssist : LocalDraftAssist {
    override suspend fun refine(
        panels: List<CapturedPanel>,
        baseline: ExtractionResult,
    ): ExtractionAssistSuggestion? = null
}
