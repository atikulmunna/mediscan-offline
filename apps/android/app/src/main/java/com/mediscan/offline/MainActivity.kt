package com.mediscan.offline

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.mediscan.offline.domain.CapturePanelType
import com.mediscan.offline.domain.nextIncompleteStepIndex
import com.mediscan.offline.domain.CapturedPanel
import com.mediscan.offline.domain.ExtractionResult
import com.mediscan.offline.domain.MedicineDraft
import com.mediscan.offline.domain.OcrUiState
import com.mediscan.offline.domain.buildOcrProgressState
import com.mediscan.offline.domain.updatePanelOcrText
import com.mediscan.offline.domain.upsertCapturedPanel
import com.mediscan.offline.extraction.RuleBasedExtractionPipeline
import com.mediscan.offline.ocr.MlKitOcrEngine
import com.mediscan.offline.ui.theme.MediScanTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

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
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val ocrEngine = remember(context) { MlKitOcrEngine(context.applicationContext) }
    val extractionPipeline = remember { RuleBasedExtractionPipeline() }
    val capturedPanels = rememberSaveable(
        saver = listSaver<SnapshotStateList<CapturedPanel>, String>(
            save = { panels ->
                panels.flatMap { panel ->
                    listOf(
                        panel.localUri,
                        panel.panelType.name,
                        panel.panelName,
                        panel.ocrText.orEmpty(),
                    )
                }
            },
            restore = { saved ->
                val restoredPanels = saved.chunked(4).mapNotNull { chunk ->
                    if (chunk.size != 4) {
                        null
                    } else {
                        CapturedPanel(
                            localUri = chunk[0],
                            panelType = CapturePanelType.valueOf(chunk[1]),
                            panelName = chunk[2],
                            ocrText = chunk[3].ifBlank { null },
                        )
                    }
                }
                mutableStateListOf(*restoredPanels.toTypedArray())
            },
        ),
    ) {
        mutableStateListOf<CapturedPanel>()
    }
    var selectedStepIndex by rememberSaveable { mutableStateOf(0) }
    var pendingStepType by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCaptureUri by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var ocrState by remember { mutableStateOf(OcrUiState()) }
    var extractionResult by remember { mutableStateOf<ExtractionResult?>(null) }
    var extractionMessage by rememberSaveable { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            val panelTypeName = pendingStepType ?: return@rememberLauncherForActivityResult
            val panelType = CapturePanelType.valueOf(panelTypeName)
            val outputUri = createCaptureUri(context, panelType)
            pendingCaptureUri = outputUri.toString()
        } else {
            cameraMessage = "Camera permission is required to capture packet panels offline."
            pendingStepType = null
            pendingCaptureUri = null
        }
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        val panelTypeName = pendingStepType
        val captureUri = pendingCaptureUri
        if (success && panelTypeName != null && captureUri != null) {
            val panelType = CapturePanelType.valueOf(panelTypeName)
            val panelName = guidedCaptureOrder.first { it.panelType == panelType }.title
            val updatedPanels = upsertCapturedPanel(
                capturedPanels,
                CapturedPanel(
                    localUri = captureUri,
                    panelType = panelType,
                    panelName = panelName,
                ),
            )
            capturedPanels.clear()
            capturedPanels.addAll(updatedPanels)
            selectedStepIndex = nextIncompleteStepIndex(
                requiredOrder = guidedCaptureOrder.map { it.panelType },
                panels = capturedPanels,
                currentIndex = selectedStepIndex,
            )
            cameraMessage = "${panelType.label} captured locally."
        } else if (captureUri != null) {
            context.contentResolver.delete(Uri.parse(captureUri), null, null)
            cameraMessage = "Capture cancelled. You can try the same step again."
        }
        pendingStepType = null
        pendingCaptureUri = null
    }

    LaunchedEffect(pendingCaptureUri) {
        val captureUri = pendingCaptureUri ?: return@LaunchedEffect
        takePictureLauncher.launch(Uri.parse(captureUri))
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        GuidedCaptureScreen(
            innerPadding = innerPadding,
            capturedPanels = capturedPanels,
            selectedStepIndex = selectedStepIndex,
            cameraMessage = cameraMessage,
            ocrState = ocrState,
            extractionResult = extractionResult,
            extractionMessage = extractionMessage,
            onSelectStep = { selectedStepIndex = it },
            onStartCapture = { step ->
                pendingStepType = step.panelType.name
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    pendingCaptureUri = createCaptureUri(context, step.panelType).toString()
                } else {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }
            },
            onRunOcr = {
                val panelsForOcr = capturedPanels.toList()
                ocrState = buildOcrProgressState(
                    completedCount = 0,
                    totalCount = panelsForOcr.size,
                )
                coroutineScope.launch {
                    try {
                        panelsForOcr.forEachIndexed { index, panel ->
                            val recognizedText = ocrEngine.recognizeText(panel)
                            val updatedPanels = updatePanelOcrText(
                                panels = capturedPanels,
                                panelType = panel.panelType,
                                ocrText = recognizedText.ifBlank { null },
                            )
                            capturedPanels.clear()
                            capturedPanels.addAll(updatedPanels)
                            ocrState = buildOcrProgressState(
                                completedCount = index + 1,
                                totalCount = panelsForOcr.size,
                            )
                        }
                        ocrState = OcrUiState(
                            isRunning = false,
                            completedCount = panelsForOcr.size,
                            totalCount = panelsForOcr.size,
                        )
                        extractionMessage = "OCR complete. You can now normalize the captured text into a medicine draft."
                    } catch (error: Exception) {
                        ocrState = buildOcrProgressState(
                            completedCount = ocrState.completedCount,
                            totalCount = panelsForOcr.size,
                            errorMessage = error.message ?: "OCR failed on this device.",
                        )
                    }
                }
            },
            onRunExtraction = {
                coroutineScope.launch {
                    extractionResult = extractionPipeline.extract(capturedPanels.toList())
                    extractionMessage = "Extraction draft updated from on-device OCR."
                }
            },
            onDismissMessage = { cameraMessage = null },
            onDismissExtractionMessage = { extractionMessage = null },
            onReset = {
                capturedPanels.clear()
                selectedStepIndex = 0
                pendingStepType = null
                pendingCaptureUri = null
                cameraMessage = null
                ocrState = OcrUiState()
                extractionResult = null
                extractionMessage = null
            },
        )
    }
}

@Composable
private fun GuidedCaptureScreen(
    innerPadding: PaddingValues,
    capturedPanels: List<CapturedPanel>,
    selectedStepIndex: Int,
    cameraMessage: String?,
    ocrState: OcrUiState,
    extractionResult: ExtractionResult?,
    extractionMessage: String?,
    onSelectStep: (Int) -> Unit,
    onStartCapture: (GuidedCaptureStep) -> Unit,
    onRunOcr: () -> Unit,
    onRunExtraction: () -> Unit,
    onDismissMessage: () -> Unit,
    onDismissExtractionMessage: () -> Unit,
    onReset: () -> Unit,
) {
    val completedSteps = capturedPanels.map { it.panelType }.toSet()
    val activeStep = guidedCaptureOrder[selectedStepIndex]
    val isReadyForReview = guidedCaptureOrder.all { it.panelType in completedSteps }

    if (cameraMessage != null) {
        AlertDialog(
            onDismissRequest = onDismissMessage,
            confirmButton = {
                Button(onClick = onDismissMessage) {
                    Text("OK")
                }
            },
            title = { Text("Capture Status") },
            text = { Text(cameraMessage) },
        )
    }

    if (extractionMessage != null) {
        AlertDialog(
            onDismissRequest = onDismissExtractionMessage,
            confirmButton = {
                Button(onClick = onDismissExtractionMessage) {
                    Text("OK")
                }
            },
            title = { Text("Extraction Status") },
            text = { Text(extractionMessage) },
        )
    }

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
            OcrStatusCard(
                capturedPanels = capturedPanels,
                ocrState = ocrState,
                onRunOcr = onRunOcr,
                onRunExtraction = onRunExtraction,
            )
        }

        if (extractionResult != null) {
            item {
                ExtractionDraftCard(result = extractionResult)
            }
        }

        item {
            ActiveStepCard(
                step = activeStep,
                stepNumber = selectedStepIndex + 1,
                isCompleted = activeStep.panelType in completedSteps,
                onStartCapture = { onStartCapture(activeStep) },
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
                    body = "Use the camera button above to capture the required packet panels and strip locally on the device.",
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
private fun OcrStatusCard(
    capturedPanels: List<CapturedPanel>,
    ocrState: OcrUiState,
    onRunOcr: () -> Unit,
    onRunExtraction: () -> Unit,
) {
    val canRunOcr = capturedPanels.isNotEmpty() && !ocrState.isRunning
    val canRunExtraction = capturedPanels.any { !it.ocrText.isNullOrBlank() } && !ocrState.isRunning

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Offline OCR",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (capturedPanels.isEmpty()) {
                    "Capture at least one panel before running on-device OCR."
                } else {
                    "Run the bundled ML Kit recognizer locally on the captured packet and strip images."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (ocrState.isRunning) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        text = "Processing ${ocrState.completedCount} of ${ocrState.totalCount} panels...",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else if (ocrState.totalCount > 0 && ocrState.errorMessage == null) {
                Text(
                    text = "OCR complete for ${ocrState.completedCount} panel(s).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (ocrState.errorMessage != null) {
                Text(
                    text = ocrState.errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(onClick = onRunOcr, enabled = canRunOcr) {
                Text("Run OCR")
            }
            OutlinedButton(onClick = onRunExtraction, enabled = canRunExtraction) {
                Text("Build Draft")
            }
        }
    }
}

@Composable
private fun ExtractionDraftCard(result: ExtractionResult) {
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
                text = "Normalized Draft",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            DraftField("Brand", result.draft.brandName, result.fieldSources["brand_name"])
            DraftField("Generic", result.draft.genericName, result.fieldSources["generic_name"])
            DraftField("Strength", result.draft.strength, result.fieldSources["strength"])
            DraftField("Batch", result.draft.batchNumber, result.fieldSources["batch_number"])
            DraftField("MFG", result.draft.manufactureDate, result.fieldSources["manufacture_date"])
            DraftField("EXP", result.draft.expiryDate, result.fieldSources["expiry_date"])
            DraftField("License", result.draft.licenseNumber, result.fieldSources["license_number"])
            DraftField("Quantity", result.draft.quantity, result.fieldSources["quantity"])
            DraftField("Manufacturer", result.draft.manufacturer, result.fieldSources["manufacturer"])
            Text(
                text = "Confidence: ${result.draft.confidence}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (result.reviewHints.isNotEmpty()) {
                Text(
                    text = "Review Hints",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                result.reviewHints.forEach { hint ->
                    Text(
                        text = "- $hint",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun DraftField(label: String, value: String?, source: String?) {
    Text(
        text = buildString {
            append(label)
            append(": ")
            append(value ?: "Not detected")
            if (!source.isNullOrBlank()) {
                append(" (from ")
                append(source)
                append(")")
            }
        },
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun ActiveStepCard(
    step: GuidedCaptureStep,
    stepNumber: Int,
    isCompleted: Boolean,
    onStartCapture: () -> Unit,
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
                Button(onClick = onStartCapture) {
                    Text(if (isCompleted) "Retake Photo" else "Open Camera")
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
            PanelPreview(panel.localUri)
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
                text = "Saved locally: ${panel.localUri}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!panel.ocrText.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "OCR Text",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = panel.ocrText,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelPreview(localUri: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, localUri) {
        value = runCatching {
            context.contentResolver.openInputStream(Uri.parse(localUri))?.use { input ->
                BitmapFactory.decodeStream(input)
            }
        }.getOrNull()
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = "Captured medicine panel preview",
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
    } else {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "Preview unavailable",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
                    "Captured panels are now stored locally. Next Android modules: on-device OCR, extraction, and the review screen."
                } else {
                    "Complete the guided capture flow first. After that, the same locally stored images can move into on-device OCR and review."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = onReset) {
                Text("Reset Guided Flow")
            }
        }
    }
}

private fun createCaptureUri(context: Context, panelType: CapturePanelType): Uri {
    val captureDirectory = File(context.filesDir, "captured_panels").apply { mkdirs() }
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val fileName = "${panelType.name.lowercase()}_$timestamp.jpg"
    val captureFile = File(captureDirectory, fileName)
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        captureFile,
    )
}
