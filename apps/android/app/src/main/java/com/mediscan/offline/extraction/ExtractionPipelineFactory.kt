package com.mediscan.offline.extraction

import com.mediscan.offline.BuildConfig
import com.mediscan.offline.domain.ExtractionPipeline

fun createExtractionPipeline(): ExtractionPipeline {
    val basePipeline = RuleBasedExtractionPipeline()
    val assistMode = BuildConfig.LOCAL_ASSIST_MODE
    val assist = when {
        !BuildConfig.ENABLE_GEMMA_ASSIST -> NoOpLocalDraftAssist()
        assistMode.equals("gemma", ignoreCase = true) -> GemmaStubLocalDraftAssist()
        assistMode.equals("gemma_sample", ignoreCase = true) -> GemmaStubLocalDraftAssist(
            responseProvider = { payload ->
                SampleGemmaResponseProvider().generate(payload)
            },
        )
        else -> NoOpLocalDraftAssist()
    }

    return AssistedExtractionPipeline(
        basePipeline = basePipeline,
        draftAssist = assist,
        shouldAssist = { result ->
            when {
                !BuildConfig.ENABLE_GEMMA_ASSIST -> false
                assistMode.equals("gemma_sample", ignoreCase = true) -> true
                assistMode.equals("gemma", ignoreCase = true) -> shouldUseAssist(result)
                else -> false
            }
        },
    )
}

private fun shouldUseAssist(result: com.mediscan.offline.domain.ExtractionResult): Boolean {
    val draft = result.draft
    val brand = draft.brandName.orEmpty().trim()
    val generic = draft.genericName.orEmpty().trim()

    return draft.confidence == "low" ||
        brand.isBlank() ||
        generic.isBlank() ||
        brand.firstOrNull()?.isLowerCase() == true ||
        brand.length < 5 ||
        brand.contains("iapa", ignoreCase = true) ||
        generic.equals("Caffeine 65 mg", ignoreCase = true)
}
