package com.mediscan.offline.domain

data class CapturedPanel(
    val localUri: String,
    val panelType: CapturePanelType,
    val panelName: String,
    val ocrText: String? = null,
    val focusedOcrText: String? = null,
)
