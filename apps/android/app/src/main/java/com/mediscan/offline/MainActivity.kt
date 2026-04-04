package com.mediscan.offline

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mediscan.offline.domain.CapturePanelType
import com.mediscan.offline.domain.CapturedPanel
import com.mediscan.offline.ui.theme.MediScanTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MediScanTheme {
                OfflineApp()
            }
        }
    }
}

private val guidedCaptureOrder = listOf(
    GuidedCaptureStep(
        panelType = CapturePanelType.PacketDateSide,
        title = "Packet Date Side",
        hint = "Capture the packet side with batch number, manufacture date, expiry date, and license information.",
        fields = listOf("Batch", "Manufacture Date", "Expiry", "License"),
    ),
    GuidedCaptureStep(
        panelType = CapturePanelType.PacketDetailSide,
        title = "Packet Detail Side",
        hint = "Capture the packet side that shows brand, generic name, strength, and pack quantity.",
        fields = listOf("Brand", "Generic", "Strength", "Quantity"),
    ),
    GuidedCaptureStep(
        panelType = CapturePanelType.Strip,
        title = "Strip",
        hint = "Capture the medicine strip for a second source of brand, generic name, and strength.",
        fields = listOf("Brand", "Generic", "Strength"),
    ),
)

private data class GuidedCaptureStep(
    val panelType: CapturePanelType,
    val title: String,
    val hint: String,
    val fields: List<String>,
)

private val panelSaver = listSaver<CapturedPanel, String>(
    save = { panel ->
        listOf(
            panel.localUri,
            panel.panelType.name,
            panel.panelName,
            panel.ocrText.orEmpty(),
        )
    },
    restore = { values ->
        CapturedPanel(
            localUri = values[0],
            panelType = CapturePanelType.valueOf(values[1]),
            panelName = values[2],
            ocrText = values[3].ifBlank { null },
        )
    },
)

@Composable
private fun OfflineApp() {
    val capturedPanels = rememberSaveable(saver = listSaver(
        save = { panels -> panels.flatMap(panelSaver::save) },
        restore = { saved ->
            saved.chunked(4).map { chunk -> panelSaver.restore(chunk)!! }.toMutableList()
        },
    )) {
        mutableStateListOf<CapturedPanel>()
    }
    var selectedStepIndex by rememberSaveable { mutableStateOf(0) }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        GuidedCaptureScreen(
            innerPadding = innerPadding,
            capturedPanels = capturedPanels,
            selectedStepIndex = selectedStepIndex,
            onSelectStep = { selectedStepIndex = it },
            onMarkCaptured = { step ->
                val panelName = "${step.title} ${capturedPanels.count { it.panelType == step.panelType } + 1}"
                capturedPanels.removeAll { it.panelType == step.panelType }
                capturedPanels.add(
                    CapturedPanel(
                        localUri = "pending://camera/${step.panelType.name.lowercase()}",
                        panelType = step.panelType,
                        panelName = panelName,
                    ),
                )
                selectedStepIndex = (selectedStepIndex + 1).coerceAtMost(guidedCaptureOrder.lastIndex)
            },
            onReset = {
                capturedPanels.clear()
                selectedStepIndex = 0
            },
        )
    }
}

@Composable
private fun GuidedCaptureScreen(
    innerPadding: PaddingValues,
    capturedPanels: List<CapturedPanel>,
    selectedStepIndex: Int,
    onSelectStep: (Int) -> Unit,
    onMarkCaptured: (GuidedCaptureStep) -> Unit,
    onReset: () -> Unit,
) {
    val completedSteps = capturedPanels.map { it.panelType }.toSet()
    val activeStep = guidedCaptureOrder[selectedStepIndex]
    val isReadyForReview = guidedCaptureOrder.all { it.panelType in completedSteps }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                text = "MediScan Offline Android",
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Guided capture is the first native Android module. The app now follows the real packet model instead of guessing from a single image.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        item {
            ProgressCard(
                completedCount = completedSteps.size,
                totalCount = guidedCaptureOrder.size,
                isReadyForReview = isReadyForReview,
            )
        }

        item {
            ActiveStepCard(
                step = activeStep,
                stepNumber = selectedStepIndex + 1,
                isCompleted = activeStep.panelType in completedSteps,
                onMarkCaptured = { onMarkCaptured(activeStep) },
            )
        }

        item {
            Text(
                text = "Capture Plan",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }

        itemsIndexed(guidedCaptureOrder) { index, step ->
            StepCard(
                index = index,
                step = step,
                isSelected = index == selectedStepIndex,
                isCompleted = step.panelType in completedSteps,
                onClick = { onSelectStep(index) },
            )
        }

        item {
            Text(
                text = "Captured Panels",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }

        if (capturedPanels.isEmpty()) {
            item {
                PlaceholderCard(
                    title = "No panels captured yet",
                    body = "Camera integration is the next Android module. For now, this flow tracks the required packet and strip captures and the order we will enforce in the native app.",
                )
            }
        } else {
            itemsIndexed(capturedPanels) { _, panel ->
                CapturedPanelCard(panel = panel)
            }
        }

        item {
            FooterCard(
                isReadyForReview = isReadyForReview,
                onReset = onReset,
            )
        }
    }
}

@Composable
private fun ProgressCard(
    completedCount: Int,
    totalCount: Int,
    isReadyForReview: Boolean,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Offline Guided Capture",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Completed $completedCount of $totalCount required captures.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = if (isReadyForReview) {
                    "The draft can move to OCR and review once camera and OCR integration land."
                } else {
                    "Finish all three required captures so the extraction layer can prioritize the right panel for each field."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ActiveStepCard(
    step: GuidedCaptureStep,
    stepNumber: Int,
    isCompleted: Boolean,
    onMarkCaptured: () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Current Step $stepNumber",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = step.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = step.hint,
                style = MaterialTheme.typography.bodyMedium,
            )
            StepFieldChips(step.fields)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onMarkCaptured) {
                    Text(if (isCompleted) "Replace Capture" else "Mark Captured")
                }
                if (isCompleted) {
                    Text(
                        text = "Captured",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
            }
        }
    }
}

@Composable
private fun StepCard(
    index: Int,
    step: GuidedCaptureStep,
    isSelected: Boolean,
    isCompleted: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = when {
        isCompleted -> MaterialTheme.colorScheme.primary
        isSelected -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        tonalElevation = if (isSelected) 3.dp else 0.dp,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(borderColor),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (isCompleted) "✓" else "${index + 1}",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = step.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = step.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StepFieldChips(fields: List<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        fields.forEach { field ->
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(999.dp),
            ) {
                Text(
                    text = field,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun CapturedPanelCard(panel: CapturedPanel) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = panel.panelName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = panel.panelType.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Saved URI placeholder: ${panel.localUri}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PlaceholderCard(title: String, body: String) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun FooterCard(
    isReadyForReview: Boolean,
    onReset: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "What Comes Next",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (isReadyForReview) {
                    "Next Android modules: camera capture, local image persistence, on-device OCR, and the review screen."
                } else {
                    "Complete the guided capture flow first. After that, we will plug the camera into the same step order and pass the captured images into the on-device OCR pipeline."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = onReset) {
                Text("Reset Guided Flow")
            }
        }
    }
}
