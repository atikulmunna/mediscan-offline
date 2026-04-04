package com.mediscan.offline.extraction

import com.mediscan.offline.domain.CapturePanelType
import com.mediscan.offline.domain.CapturedPanel
import com.mediscan.offline.domain.ExtractionPipeline
import com.mediscan.offline.domain.ExtractionResult
import com.mediscan.offline.domain.MedicineDraft

class RuleBasedExtractionPipeline : ExtractionPipeline {
    override suspend fun extract(panels: List<CapturedPanel>): ExtractionResult {
        val groupedLines = panels.associate { panel ->
            panel.panelType to cleanLines(panel.ocrText.orEmpty())
        }

        val packetDateLines = groupedLines[CapturePanelType.PacketDateSide].orEmpty()
        val packetDetailLines = groupedLines[CapturePanelType.PacketDetailSide].orEmpty()
        val stripLines = groupedLines[CapturePanelType.Strip].orEmpty()
        val allLines = buildList {
            addAll(packetDateLines)
            addAll(packetDetailLines)
            addAll(stripLines)
        }
        val allText = allLines.joinToString("\n")

        val brandCandidate = detectBrandName(stripLines, packetDetailLines, allLines)
        val genericCandidate = detectGenericName(stripLines, packetDetailLines, allLines)
        val manufacturerCandidate = detectManufacturer(allLines)
        val draft = MedicineDraft(
            brandName = correctBrandName(brandCandidate),
            genericName = correctGenericLine(genericCandidate),
            manufacturer = correctManufacturerName(manufacturerCandidate),
            batchNumber = detectBatch(packetDateLines.joinToString("\n")),
            strength = detectStrength(listOfNotNull(genericCandidate, brandCandidate, allText).joinToString("\n")),
            quantity = detectQuantity(packetDetailLines.joinToString("\n")),
            manufactureDate = detectManufactureDate(packetDateLines.joinToString("\n")),
            expiryDate = detectExpiryDate(packetDateLines.joinToString("\n")),
            licenseNumber = detectLicense(packetDateLines.joinToString("\n")),
            activeIngredients = correctGenericLine(detectActiveIngredients(allLines)),
            confidence = inferConfidence(
                brandName = correctBrandName(brandCandidate),
                genericName = correctGenericLine(genericCandidate),
                batchNumber = detectBatch(packetDateLines.joinToString("\n")),
                expiryDate = detectExpiryDate(packetDateLines.joinToString("\n")),
            ),
        )

        val fieldSources = buildMap {
            if (draft.brandName != null) put("brand_name", preferredSourceName(brandCandidate, stripLines, packetDetailLines))
            if (draft.genericName != null) put("generic_name", preferredSourceName(genericCandidate, stripLines, packetDetailLines))
            if (draft.strength != null) put("strength", preferredSourceName(draft.strength, stripLines, packetDetailLines))
            if (draft.batchNumber != null) put("batch_number", CapturePanelType.PacketDateSide.label)
            if (draft.manufactureDate != null) put("manufacture_date", CapturePanelType.PacketDateSide.label)
            if (draft.expiryDate != null) put("expiry_date", CapturePanelType.PacketDateSide.label)
            if (draft.licenseNumber != null) put("license_number", CapturePanelType.PacketDateSide.label)
            if (draft.quantity != null) put("quantity", CapturePanelType.PacketDetailSide.label)
            if (manufacturerCandidate != null) put("manufacturer", sourceNameForLine(manufacturerCandidate, allLines, panels))
        }

        val reviewHints = buildList {
            if (draft.brandName == null) add("Brand name missing: verify the strip or packet detail side.")
            if (draft.genericName == null) add("Generic name missing: review the strip text manually.")
            if (draft.batchNumber == null) add("Batch number missing: capture the packet date side more clearly.")
            if (draft.expiryDate == null) add("Expiry date missing: capture the packet date side more clearly.")
            if (draft.manufactureDate == null) add("Manufacture date missing: verify from the packet date side.")
            if (draft.strength == null) add("Strength missing: verify the strip or detail side.")
            if (draft.confidence == "low") add("Low extraction confidence: retake the image with better lighting or angle.")
        }

        return ExtractionResult(
            draft = draft,
            reviewHints = reviewHints,
            fieldSources = fieldSources,
        )
    }
}

private fun cleanLines(rawText: String): List<String> {
    return rawText
        .lines()
        .map { it.trim() }
        .filter { it.isNotBlank() }
}

private fun detectBrandName(
    stripLines: List<String>,
    packetDetailLines: List<String>,
    allLines: List<String>,
): String? {
    val candidateLines = stripLines + packetDetailLines + allLines
    for (line in candidateLines) {
        val compact = normalizeToken(line)
        if (correctBrandName(line) != line.trim() || compact in setOf("naprosyn500", "naprosyn", "naprosy")) {
            return line
        }
    }

    return candidateLines
        .filter { line ->
            val lowered = line.lowercase()
            val letters = line.count { it.isLetter() }
            val digits = line.count { it.isDigit() }
            letters >= 3 &&
                digits <= letters &&
                !lowered.contains("batch") &&
                !lowered.contains("mfg") &&
                !lowered.contains("exp") &&
                !lowered.contains("limited") &&
                !lowered.contains("pharma") &&
                !Regex("\\b\\d+(?:\\.\\d+)?\\s?(?:mg|mcg|g|ml)\\b").containsMatchIn(lowered)
        }
        .maxByOrNull { line ->
            val bonus = if (line.any { it.isDigit() }) 2 else 0
            line.length + bonus
        }
}

private fun detectGenericName(
    stripLines: List<String>,
    packetDetailLines: List<String>,
    allLines: List<String>,
): String? {
    val candidateLines = stripLines + packetDetailLines + allLines
    val preferred = candidateLines.firstOrNull { line ->
        val lowered = line.lowercase()
        Regex("\\b\\d+(?:\\.\\d+)?\\s?(?:mg|mcg|g|ml)\\b").containsMatchIn(lowered) &&
            listOf(
                "naproxen",
                "paracetamol",
                "esomeprazole",
                "rupatadine",
                "metronidazole",
                "melatonin",
                "flucloxacillin",
                "montelukast",
                "flunarizine",
                "cranberry",
                "calcium",
                "caffeine",
            ).any { lowered.contains(it) || normalizeToken(lowered).contains(it.removePrefix("").replace(" ", "")) }
    }
    if (preferred != null) {
        return preferred
    }

    return candidateLines.firstOrNull { line ->
        val lowered = line.lowercase()
        Regex("\\b\\d+(?:\\.\\d+)?\\s?(?:mg|mcg|g|ml)\\b").containsMatchIn(lowered) &&
            !lowered.contains("batch") &&
            !lowered.contains("mfg") &&
            !lowered.contains("exp")
    }
}

private fun detectStrength(text: String): String? {
    return Regex("\\b(\\d+(?:\\.\\d+)?\\s?(?:mg|mcg|g|ml))\\b", RegexOption.IGNORE_CASE)
        .find(text)
        ?.groupValues
        ?.get(1)
}

private fun detectQuantity(text: String): String? {
    return Regex("\\b(\\d+\\s?(?:tablets?|capsules?|strips?|sachets?|ampoules?|vials?))\\b", RegexOption.IGNORE_CASE)
        .find(text)
        ?.groupValues
        ?.get(1)
}

private fun detectBatch(text: String): String? {
    return Regex("(?:batch|b\\.?no|lot)\\s*[:#]?\\s*([A-Z0-9\\-/]+)", RegexOption.IGNORE_CASE)
        .find(text)
        ?.groupValues
        ?.get(1)
}

private fun detectManufactureDate(text: String): String? {
    return Regex("(?:mfg|manufactured|manufacture date)\\s*[:#]?\\s*([A-Z0-9\\-/ ]+)", RegexOption.IGNORE_CASE)
        .find(text)
        ?.groupValues
        ?.get(1)
        ?.trim()
}

private fun detectExpiryDate(text: String): String? {
    return Regex("(?:exp|expiry|expires|expiry date)\\s*[:#]?\\s*([A-Z0-9\\-/ ]+)", RegexOption.IGNORE_CASE)
        .find(text)
        ?.groupValues
        ?.get(1)
        ?.trim()
}

private fun detectLicense(text: String): String? {
    return Regex("(?:license|licence|reg(?:istration)?\\.?\\s*no|ma no)\\s*[:#]?\\s*([A-Z0-9\\-/]+)", RegexOption.IGNORE_CASE)
        .find(text)
        ?.groupValues
        ?.get(1)
}

private fun detectManufacturer(lines: List<String>): String? {
    return lines.firstOrNull { line ->
        val lowered = line.lowercase()
        lowered.contains("limited") ||
            lowered.contains("ltd") ||
            lowered.contains("pharma") ||
            lowered.contains("pharmaceutical") ||
            lowered.contains("healthcare")
    }
}

private fun detectActiveIngredients(lines: List<String>): String? {
    return lines.firstOrNull { line ->
        val lowered = line.lowercase()
        lowered.contains("contains") ||
            lowered.contains("composition") ||
            (Regex("\\b\\d+(?:\\.\\d+)?\\s?(?:mg|mcg|g|ml)\\b").containsMatchIn(lowered) &&
                listOf("naproxen", "paracetamol", "esomeprazole", "rupatadine", "metronidazole").any { lowered.contains(it) })
    }
}

private fun inferConfidence(
    brandName: String?,
    genericName: String?,
    batchNumber: String?,
    expiryDate: String?,
): String {
    val filled = listOf(brandName, genericName, batchNumber, expiryDate).count { !it.isNullOrBlank() }
    return when {
        filled >= 4 -> "high"
        filled >= 2 -> "medium"
        else -> "low"
    }
}

private fun preferredSourceName(
    value: String?,
    stripLines: List<String>,
    packetDetailLines: List<String>,
): String {
    return when {
        value != null && stripLines.any { it.contains(value, ignoreCase = true) } -> CapturePanelType.Strip.label
        else -> CapturePanelType.PacketDetailSide.label
    }
}

private fun sourceNameForLine(
    value: String,
    allLines: List<String>,
    panels: List<CapturedPanel>,
): String {
    val panel = panels.firstOrNull { panel ->
        panel.ocrText.orEmpty().lines().any { it.contains(value, ignoreCase = true) }
    }
    return panel?.panelType?.label ?: CapturePanelType.PacketDetailSide.label
}
