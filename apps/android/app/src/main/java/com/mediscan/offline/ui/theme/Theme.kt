package com.mediscan.offline.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val MediScanColors = darkColorScheme()

@Composable
fun MediScanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MediScanColors,
        content = content,
    )
}
