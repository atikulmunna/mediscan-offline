package com.mediscan.offline.domain

data class OcrUiState(
    val isRunning: Boolean = false,
    val completedCount: Int = 0,
    val totalCount: Int = 0,
    val errorMessage: String? = null,
)

fun updatePanelOcrText(
    panels: List<CapturedPanel>,
    panelType: CapturePanelType,
    ocrText: String?,
): List<CapturedPanel> {
    return panels.map { panel ->
        if (panel.panelType == panelType) {
            panel.copy(ocrText = ocrText)
        } else {
            panel
        }
    }
}

fun buildOcrProgressState(
    completedCount: Int,
    totalCount: Int,
    errorMessage: String? = null,
): OcrUiState {
    return OcrUiState(
        isRunning = completedCount in 0 until totalCount && errorMessage == null,
        completedCount = completedCount,
        totalCount = totalCount,
        errorMessage = errorMessage,
    )
}
