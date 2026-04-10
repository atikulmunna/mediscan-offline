package com.mediscan.offline.extraction

import com.mediscan.offline.BuildConfig
import com.mediscan.offline.domain.ExtractionPipeline

fun createExtractionPipeline(): ExtractionPipeline {
    val basePipeline = RuleBasedExtractionPipeline()
    val assist = when {
        !BuildConfig.ENABLE_GEMMA_ASSIST -> NoOpLocalDraftAssist()
        BuildConfig.LOCAL_ASSIST_MODE.equals("gemma", ignoreCase = true) -> GemmaStubLocalDraftAssist()
        else -> NoOpLocalDraftAssist()
    }

    return AssistedExtractionPipeline(
        basePipeline = basePipeline,
        draftAssist = assist,
    )
}
