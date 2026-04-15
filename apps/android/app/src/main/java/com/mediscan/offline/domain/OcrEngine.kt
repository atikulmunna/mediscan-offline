package com.mediscan.offline.domain

data class OcrRecognitionResult(
    val mergedText: String,
    val focusedText: String? = null,
)

interface OcrEngine {
    suspend fun recognizeText(panel: CapturedPanel): OcrRecognitionResult
}
