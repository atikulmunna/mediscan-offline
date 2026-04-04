package com.mediscan.offline.domain

fun upsertCapturedPanel(
    panels: List<CapturedPanel>,
    updatedPanel: CapturedPanel,
): List<CapturedPanel> {
    val remainingPanels = panels.filterNot { it.panelType == updatedPanel.panelType }
    return remainingPanels + updatedPanel
}

fun nextIncompleteStepIndex(
    requiredOrder: List<CapturePanelType>,
    panels: List<CapturedPanel>,
    currentIndex: Int,
): Int {
    val completedTypes = panels.map { it.panelType }.toSet()
    val nextIndex = requiredOrder.indexOfFirst { it !in completedTypes }
    return when {
        nextIndex >= 0 -> nextIndex
        requiredOrder.isEmpty() -> 0
        else -> currentIndex.coerceIn(0, requiredOrder.lastIndex)
    }
}
