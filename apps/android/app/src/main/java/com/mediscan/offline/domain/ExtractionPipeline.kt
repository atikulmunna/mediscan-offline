package com.mediscan.offline.domain

interface ExtractionPipeline {
    suspend fun extract(panels: List<CapturedPanel>): ExtractionResult
}

data class ExtractionResult(
    val draft: MedicineDraft,
    val reviewHints: List<String> = emptyList(),
    val fieldSources: Map<String, String> = emptyMap(),
    val assistApplied: Boolean = false,
    val assistProvider: String? = null,
)
