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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.OutlinedTextField
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
import com.mediscan.offline.data.local.MediScanDatabase
import com.mediscan.offline.data.local.MedicineEntity
import com.mediscan.offline.data.local.applyDraft
import com.mediscan.offline.data.local.toDraft
import com.mediscan.offline.data.local.toEntity
import com.mediscan.offline.domain.CapturePanelType
import com.mediscan.offline.domain.nextIncompleteStepIndex
import com.mediscan.offline.domain.CapturedPanel
import com.mediscan.offline.domain.ExtractionResult
import com.mediscan.offline.domain.MedicineDraft
import com.mediscan.offline.domain.OcrUiState
import com.mediscan.offline.domain.buildOcrProgressState
import com.mediscan.offline.domain.updatePanelOcrText
import com.mediscan.offline.domain.upsertCapturedPanel
import com.mediscan.offline.extraction.createExtractionPipeline
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
            panel.focusedOcrText.orEmpty(),
        )
    },
    restore = { values ->
        CapturedPanel(
            localUri = values[0],
            panelType = CapturePanelType.valueOf(values[1]),
            panelName = values[2],
            ocrText = values[3].ifBlank { null },
            focusedOcrText = values.getOrNull(4).orEmpty().ifBlank { null },
        )
    },
)

@Composable
private fun OfflineApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val ocrEngine = remember(context) { MlKitOcrEngine(context.applicationContext) }
    val extractionPipeline = remember { createExtractionPipeline() }
    val medicineDao = remember(context) { MediScanDatabase.getInstance(context).medicineDao() }
    val capturedPanels = rememberSaveable(
        saver = listSaver<SnapshotStateList<CapturedPanel>, String>(
            save = { panels ->
                panels.flatMap { panel ->
                    listOf(
                        panel.localUri,
                        panel.panelType.name,
                        panel.panelName,
                        panel.ocrText.orEmpty(),
                        panel.focusedOcrText.orEmpty(),
                    )
                }
            },
            restore = { saved ->
                val panelChunks = when {
                    saved.size % 5 == 0 -> saved.chunked(5)
                    saved.size % 4 == 0 -> saved.chunked(4)
                    else -> emptyList()
                }
                val restoredPanels = panelChunks.mapNotNull { chunk ->
                    if (chunk.size < 4) {
                        null
                    } else {
                        CapturedPanel(
                            localUri = chunk[0],
                            panelType = CapturePanelType.valueOf(chunk[1]),
                            panelName = chunk[2],
                            ocrText = chunk[3].ifBlank { null },
                            focusedOcrText = chunk.getOrNull(4).orEmpty().ifBlank { null },
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
    var pendingGalleryStepType by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var ocrState by remember { mutableStateOf(OcrUiState()) }
    var extractionResult by remember { mutableStateOf<ExtractionResult?>(null) }
    var extractionMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var editableDraft by remember { mutableStateOf<MedicineDraft?>(null) }
    var saveMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var savedRecords by remember { mutableStateOf<List<MedicineEntity>>(emptyList()) }
    var savedSearchQuery by rememberSaveable { mutableStateOf("") }
    var savedConfidenceFilter by rememberSaveable { mutableStateOf("all") }
    var selectedSavedRecord by remember { mutableStateOf<MedicineEntity?>(null) }
    var editableSavedDraft by remember { mutableStateOf<MedicineDraft?>(null) }

    suspend fun refreshSavedRecords() {
        val trimmedQuery = savedSearchQuery.trim()
        val confidence = savedConfidenceFilter.takeUnless { it == "all" }
        savedRecords = medicineDao.search(trimmedQuery, confidence)
    }

    LaunchedEffect(savedSearchQuery, savedConfidenceFilter) {
        refreshSavedRecords()
    }

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

    val pickImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { selectedUri ->
        val panelTypeName = pendingGalleryStepType
        if (selectedUri != null && panelTypeName != null) {
            val panelType = CapturePanelType.valueOf(panelTypeName)
            val importedUri = importGalleryImage(context, selectedUri, panelType)
            if (importedUri != null) {
                val panelName = guidedCaptureOrder.first { it.panelType == panelType }.title
                val updatedPanels = upsertCapturedPanel(
                    capturedPanels,
                    CapturedPanel(
                        localUri = importedUri.toString(),
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
                cameraMessage = "${panelType.label} imported from gallery."
            } else {
                cameraMessage = "Could not import the selected gallery image."
            }
        }
        pendingGalleryStepType = null
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
            editableDraft = editableDraft,
            saveMessage = saveMessage,
            savedRecords = savedRecords,
            savedSearchQuery = savedSearchQuery,
            savedConfidenceFilter = savedConfidenceFilter,
            selectedSavedRecord = selectedSavedRecord,
            editableSavedDraft = editableSavedDraft,
            onSelectStep = { selectedStepIndex = it },
            onStartCapture = { step ->
                pendingStepType = step.panelType.name
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    pendingCaptureUri = createCaptureUri(context, step.panelType).toString()
                } else {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }
            },
            onImportFromGallery = { step ->
                pendingGalleryStepType = step.panelType.name
                pickImageLauncher.launch("image/*")
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
                            val recognized = ocrEngine.recognizeText(panel)
                            val updatedPanels = updatePanelOcrText(
                                panels = capturedPanels,
                                panelType = panel.panelType,
                                ocrText = recognized.mergedText.ifBlank { null },
                                focusedOcrText = recognized.focusedText?.ifBlank { null },
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
                    editableDraft = extractionResult?.draft
                    extractionMessage = "Extraction draft updated from on-device OCR."
                }
            },
            onDismissMessage = { cameraMessage = null },
            onDismissExtractionMessage = { extractionMessage = null },
            onDraftChange = { editableDraft = it },
            onSaveDraft = {
                val draftToSave = editableDraft ?: return@GuidedCaptureScreen
                coroutineScope.launch {
                    medicineDao.insert(draftToSave.toEntity(capturedPanels.toList()))
                    refreshSavedRecords()
                    saveMessage = "Medicine saved locally to Room/SQLite."
                }
            },
            onDismissSaveMessage = { saveMessage = null },
            onSavedSearchQueryChange = { savedSearchQuery = it },
            onSavedConfidenceFilterChange = { savedConfidenceFilter = it },
            onOpenSavedRecord = { record ->
                selectedSavedRecord = record
                editableSavedDraft = record.toDraft()
            },
            onSavedDraftChange = { editableSavedDraft = it },
            onSaveExistingRecord = {
                val record = selectedSavedRecord ?: return@GuidedCaptureScreen
                val draft = editableSavedDraft ?: return@GuidedCaptureScreen
                coroutineScope.launch {
                    medicineDao.update(record.applyDraft(draft))
                    refreshSavedRecords()
                    selectedSavedRecord = medicineDao.findById(record.id)
                    editableSavedDraft = selectedSavedRecord?.toDraft()
                    saveMessage = "Saved medicine updated locally."
                }
            },
            onCloseSavedRecord = {
                selectedSavedRecord = null
                editableSavedDraft = null
            },
            onReset = {
                capturedPanels.clear()
                selectedStepIndex = 0
                pendingStepType = null
                pendingCaptureUri = null
                pendingGalleryStepType = null
                cameraMessage = null
                ocrState = OcrUiState()
                extractionResult = null
                extractionMessage = null
                editableDraft = null
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
    editableDraft: MedicineDraft?,
    saveMessage: String?,
    savedRecords: List<MedicineEntity>,
    savedSearchQuery: String,
    savedConfidenceFilter: String,
    selectedSavedRecord: MedicineEntity?,
    editableSavedDraft: MedicineDraft?,
    onSelectStep: (Int) -> Unit,
    onStartCapture: (GuidedCaptureStep) -> Unit,
    onImportFromGallery: (GuidedCaptureStep) -> Unit,
    onRunOcr: () -> Unit,
    onRunExtraction: () -> Unit,
    onDismissMessage: () -> Unit,
    onDismissExtractionMessage: () -> Unit,
    onDraftChange: (MedicineDraft) -> Unit,
    onSaveDraft: () -> Unit,
    onDismissSaveMessage: () -> Unit,
    onSavedSearchQueryChange: (String) -> Unit,
    onSavedConfidenceFilterChange: (String) -> Unit,
    onOpenSavedRecord: (MedicineEntity) -> Unit,
    onSavedDraftChange: (MedicineDraft) -> Unit,
    onSaveExistingRecord: () -> Unit,
    onCloseSavedRecord: () -> Unit,
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

    if (saveMessage != null) {
        AlertDialog(
            onDismissRequest = onDismissSaveMessage,
            confirmButton = {
                Button(onClick = onDismissSaveMessage) {
                    Text("OK")
                }
            },
            title = { Text("Save Status") },
            text = { Text(saveMessage) },
        )
    }

    if (selectedSavedRecord != null && editableSavedDraft != null) {
        AlertDialog(
            onDismissRequest = onCloseSavedRecord,
            confirmButton = {
                Button(onClick = onSaveExistingRecord) {
                    Text("Update")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = onCloseSavedRecord) {
                    Text("Close")
                }
            },
            title = {
                Text(selectedSavedRecord.brandName ?: "Saved Medicine")
            },
            text = {
                SavedRecordEditor(
                    record = selectedSavedRecord,
                    draft = editableSavedDraft,
                    onDraftChange = onSavedDraftChange,
                )
            },
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

        if (editableDraft != null) {
            item {
                ReviewDraftCard(
                    draft = editableDraft,
                    onDraftChange = onDraftChange,
                    onSaveDraft = onSaveDraft,
                )
            }
        }

        item {
            ActiveStepCard(
                step = activeStep,
                stepNumber = selectedStepIndex + 1,
                isCompleted = activeStep.panelType in completedSteps,
                onStartCapture = { onStartCapture(activeStep) },
                onImportFromGallery = { onImportFromGallery(activeStep) },
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

        item {
            SavedRecordsCard(
                records = savedRecords,
                searchQuery = savedSearchQuery,
                confidenceFilter = savedConfidenceFilter,
                onSearchQueryChange = onSavedSearchQueryChange,
                onConfidenceFilterChange = onSavedConfidenceFilterChange,
                onOpenRecord = onOpenSavedRecord,
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
            if (BuildConfig.ENABLE_GEMMA_ASSIST) {
                Text(
                    text = "Assist Mode: ${humanizeAssistMode(BuildConfig.LOCAL_ASSIST_MODE)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Assist Applied: ${if (result.assistApplied) "Yes" else "No"}" +
                        (result.assistProvider?.let { " ($it)" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
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
private fun ReviewDraftCard(
    draft: MedicineDraft,
    onDraftChange: (MedicineDraft) -> Unit,
    onSaveDraft: () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Review And Edit",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            DraftTextField("Brand Name", draft.brandName) { onDraftChange(draft.copy(brandName = it)) }
            DraftTextField("Generic Name", draft.genericName) { onDraftChange(draft.copy(genericName = it)) }
            DraftTextField("Manufacturer", draft.manufacturer) { onDraftChange(draft.copy(manufacturer = it)) }
            DraftTextField("Strength", draft.strength) { onDraftChange(draft.copy(strength = it)) }
            DraftTextField("Batch Number", draft.batchNumber) { onDraftChange(draft.copy(batchNumber = it)) }
            DraftTextField("Manufacture Date", draft.manufactureDate) { onDraftChange(draft.copy(manufactureDate = it)) }
            DraftTextField("Expiry Date", draft.expiryDate) { onDraftChange(draft.copy(expiryDate = it)) }
            DraftTextField("License Number", draft.licenseNumber) { onDraftChange(draft.copy(licenseNumber = it)) }
            DraftTextField("Quantity", draft.quantity) { onDraftChange(draft.copy(quantity = it)) }
            DraftTextField("Active Ingredients", draft.activeIngredients) { onDraftChange(draft.copy(activeIngredients = it)) }
            Button(onClick = onSaveDraft, modifier = Modifier.fillMaxWidth()) {
                Text("Save Locally")
            }
        }
    }
}

@Composable
private fun DraftTextField(
    label: String,
    value: String?,
    onValueChange: (String?) -> Unit,
) {
    OutlinedTextField(
        value = value.orEmpty(),
        onValueChange = { updated ->
            onValueChange(updated.ifBlank { null })
        },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = false,
    )
}

@Composable
private fun SavedRecordsCard(
    records: List<MedicineEntity>,
    searchQuery: String,
    confidenceFilter: String,
    onSearchQueryChange: (String) -> Unit,
    onConfidenceFilterChange: (String) -> Unit,
    onOpenRecord: (MedicineEntity) -> Unit,
) {
    val confidenceOptions = listOf("all", "high", "medium", "low")

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
                text = "Saved Medicines",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                label = { Text("Search brand, generic, manufacturer") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Text(
                text = "Confidence Filter",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                confidenceOptions.forEach { option ->
                    val label = option.replaceFirstChar { it.uppercase() }
                    if (confidenceFilter == option) {
                        Button(
                            onClick = { onConfidenceFilterChange(option) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(label)
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onConfidenceFilterChange(option) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(label)
                        }
                    }
                }
            }
            Text(
                text = "${records.size} saved medicine(s) match the current filter.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (records.isEmpty()) {
                Text(
                    text = if (searchQuery.isBlank() && confidenceFilter == "all") {
                        "No medicines saved locally yet."
                    } else {
                        "No saved medicines match the current search or confidence filter."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                records.take(20).forEach { record ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenRecord(record) },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = record.brandName ?: "Unnamed medicine",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = record.genericName ?: "Generic not saved",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = "Confidence: ${record.confidence}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = "Saved: ${record.scannedAt}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (records.size > 20) {
                    Text(
                        text = "Showing the first 20 matches. Narrow the search to find a specific record faster.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedRecordEditor(
    record: MedicineEntity,
    draft: MedicineDraft,
    onDraftChange: (MedicineDraft) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Saved ${record.scannedAt}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DraftTextField("Brand Name", draft.brandName) { onDraftChange(draft.copy(brandName = it)) }
        DraftTextField("Generic Name", draft.genericName) { onDraftChange(draft.copy(genericName = it)) }
        DraftTextField("Manufacturer", draft.manufacturer) { onDraftChange(draft.copy(manufacturer = it)) }
        DraftTextField("Strength", draft.strength) { onDraftChange(draft.copy(strength = it)) }
        DraftTextField("Batch Number", draft.batchNumber) { onDraftChange(draft.copy(batchNumber = it)) }
        DraftTextField("Manufacture Date", draft.manufactureDate) { onDraftChange(draft.copy(manufactureDate = it)) }
        DraftTextField("Expiry Date", draft.expiryDate) { onDraftChange(draft.copy(expiryDate = it)) }
        DraftTextField("License Number", draft.licenseNumber) { onDraftChange(draft.copy(licenseNumber = it)) }
        DraftTextField("Quantity", draft.quantity) { onDraftChange(draft.copy(quantity = it)) }
        DraftTextField("Active Ingredients", draft.activeIngredients) { onDraftChange(draft.copy(activeIngredients = it)) }
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
    onImportFromGallery: () -> Unit,
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
                OutlinedButton(onClick = onImportFromGallery) {
                    Text(if (isCompleted) "Replace From Gallery" else "Add From Gallery")
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
            if (!panel.focusedOcrText.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "Focused Sticker OCR",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        Text(
                            text = panel.focusedOcrText,
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

private fun importGalleryImage(
    context: Context,
    sourceUri: Uri,
    panelType: CapturePanelType,
): Uri? {
    return runCatching {
        val targetUri = createCaptureUri(context, panelType)
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            context.contentResolver.openOutputStream(targetUri, "w")?.use { output ->
                input.copyTo(output)
            } ?: error("Could not open target output stream.")
        } ?: error("Could not open selected gallery image.")
        targetUri
    }.getOrNull()
}

private fun humanizeAssistMode(mode: String): String {
    return when {
        mode.equals("gemma_sample", ignoreCase = true) -> "Gemma Sample"
        mode.equals("gemma", ignoreCase = true) -> "Gemma"
        else -> mode.ifBlank { "None" }
    }
}
