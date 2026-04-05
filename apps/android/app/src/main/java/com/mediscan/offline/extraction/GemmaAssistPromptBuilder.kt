package com.mediscan.offline.extraction

import com.mediscan.offline.domain.CapturedPanel
import com.mediscan.offline.domain.ExtractionResult

data class GemmaAssistPayload(
    val systemPrompt: String,
    val userPrompt: String,
)

class GemmaAssistPromptBuilder {
    fun build(
        panels: List<CapturedPanel>,
        baseline: ExtractionResult,
    ): GemmaAssistPayload {
        val systemPrompt = """
            You are a local offline medicine package extraction assistant.
            Your job is to improve a draft created from on-device OCR.
            Prefer English medicine names when Bengali and English both appear.
            Do not hallucinate batch number, manufacture date, expiry date, license number, or quantity.
            Only suggest a field when there is direct evidence in the OCR text.
            Return JSON with keys:
            brand_name, generic_name, manufacturer, strength, active_ingredients, confidence, review_hints.
            review_hints must be an array of short strings.
        """.trimIndent()

        val userPrompt = buildString {
            appendLine("Baseline draft:")
            appendLine("- brand_name: ${baseline.draft.brandName ?: "null"}")
            appendLine("- generic_name: ${baseline.draft.genericName ?: "null"}")
            appendLine("- manufacturer: ${baseline.draft.manufacturer ?: "null"}")
            appendLine("- strength: ${baseline.draft.strength ?: "null"}")
            appendLine("- confidence: ${baseline.draft.confidence}")
            if (baseline.reviewHints.isNotEmpty()) {
                appendLine("- review_hints: ${baseline.reviewHints.joinToString(" | ")}")
            }
            appendLine()
            appendLine("OCR panels:")
            panels.forEachIndexed { index, panel ->
                appendLine("${index + 1}. ${panel.panelType.label}")
                appendLine("panel_name: ${panel.panelName}")
                appendLine("ocr_text:")
                appendLine(panel.ocrText.orEmpty().ifBlank { "(empty)" })
                appendLine()
            }
            appendLine("Instructions:")
            appendLine("- Prefer strip and packet detail side for brand, generic, and strength.")
            appendLine("- Prefer English medical names over Bengali transliterations when both are present.")
            appendLine("- Keep operational fields null unless clearly present.")
            appendLine("- Keep the response concise and structured.")
        }

        return GemmaAssistPayload(
            systemPrompt = systemPrompt,
            userPrompt = userPrompt.trim(),
        )
    }
}
