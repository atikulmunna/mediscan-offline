package com.mediscan.offline.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.mediscan.offline.domain.CapturePanelType
import com.mediscan.offline.domain.CapturedPanel
import com.mediscan.offline.domain.OcrEngine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class MlKitOcrEngine(
    private val context: Context,
) : OcrEngine {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun recognizeText(panel: CapturedPanel): String {
        val imageUri = Uri.parse(panel.localUri)
        val baseImage = InputImage.fromFilePath(context, imageUri)
        val baseText = recognize(baseImage)

        if (panel.panelType != CapturePanelType.PacketDateSide) {
            return baseText
        }

        val bitmap = decodeBitmap(imageUri) ?: return baseText
        val focusedTexts = buildList {
            add(baseText)
            addAll(packetDateCandidates(bitmap).mapNotNull { candidate ->
                runCatching {
                    recognize(InputImage.fromBitmap(candidate, 0))
                }.getOrNull()
            })
        }

        return mergeRecognizedTexts(focusedTexts)
    }

    private suspend fun recognize(image: InputImage): String {
        return suspendCancellableCoroutine { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    continuation.resume(result.text)
                }
                .addOnFailureListener { error ->
                    continuation.resumeWithException(error)
                }
        }
    }

    private fun decodeBitmap(uri: Uri): Bitmap? {
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream)
        }
    }
}

internal fun mergeRecognizedTexts(texts: List<String>): String {
    return texts
        .asSequence()
        .flatMap { it.lines().asSequence() }
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString("\n")
}

private fun packetDateCandidates(bitmap: Bitmap): List<Bitmap> {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) {
        return emptyList()
    }

    val candidates = mutableListOf<Bitmap>()

    fun cropRelative(
        leftRatio: Float,
        topRatio: Float,
        widthRatio: Float,
        heightRatio: Float,
        scaleMultiplier: Float = 2.0f,
    ) {
        val left = (width * leftRatio).toInt().coerceIn(0, width - 1)
        val top = (height * topRatio).toInt().coerceIn(0, height - 1)
        val cropWidth = (width * widthRatio).toInt().coerceIn(1, width - left)
        val cropHeight = (height * heightRatio).toInt().coerceIn(1, height - top)
        val cropped = Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)
        val scaled = Bitmap.createScaledBitmap(
            cropped,
            (cropWidth * scaleMultiplier).toInt().coerceAtLeast(cropWidth),
            (cropHeight * scaleMultiplier).toInt().coerceAtLeast(cropHeight),
            true,
        )
        candidates.add(scaled)
    }

    // Focus on the sticker/label block that usually contains Batch/MFG/EXP.
    cropRelative(leftRatio = 0.18f, topRatio = 0.10f, widthRatio = 0.62f, heightRatio = 0.42f)
    cropRelative(leftRatio = 0.14f, topRatio = 0.08f, widthRatio = 0.70f, heightRatio = 0.52f)
    cropRelative(leftRatio = 0.22f, topRatio = 0.14f, widthRatio = 0.56f, heightRatio = 0.30f, scaleMultiplier = 2.5f)

    return candidates
}
