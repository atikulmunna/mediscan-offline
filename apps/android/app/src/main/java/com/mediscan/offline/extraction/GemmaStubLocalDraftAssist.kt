package com.mediscan.offline.extraction

import com.mediscan.offline.domain.CapturedPanel
import com.mediscan.offline.domain.ExtractionResult

class GemmaStubLocalDraftAssist(
    private val promptBuilder: GemmaAssistPromptBuilder = GemmaAssistPromptBuilder(),
    private val responseParser: GemmaAssistResponseParser = GemmaAssistResponseParser(),
    private val responseProvider: suspend (GemmaAssistPayload) -> String? = { null },
) : LocalDraftAssist {
    override suspend fun refine(
        panels: List<CapturedPanel>,
        baseline: ExtractionResult,
    ): ExtractionAssistSuggestion? {
        val payload = promptBuilder.build(
            panels = panels,
            baseline = baseline,
        )
        val rawResponse = responseProvider(payload) ?: return null
        return responseParser.parse(rawResponse)
    }
}
