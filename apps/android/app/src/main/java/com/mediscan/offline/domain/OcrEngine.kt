package com.mediscan.offline.domain

interface OcrEngine {
    suspend fun recognizeText(panel: CapturedPanel): String
}
