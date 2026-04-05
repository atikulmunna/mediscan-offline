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
        .flatMap { expandCandidateLine(it) }
        .distinct()
}

private fun detectBrandName(
    stripLines: List<String>,
    packetDetailLines: List<String>,
    allLines: List<String>,
): String? {
    val candidateLines = stripLines + packetDetailLines + allLines
    return candidateLines
        .filter(::isBrandCandidate)
        .maxByOrNull(::brandScore)
}

private fun detectGenericName(
    stripLines: List<String>,
    packetDetailLines: List<String>,
    allLines: List<String>,
): String? {
    val candidateLines = stripLines + packetDetailLines + allLines
    return candidateLines
        .filter(::isGenericCandidate)
        .maxByOrNull(::genericScore)
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

private fun expandCandidateLine(line: String): List<String> {
    val parts = mutableListOf<String>()
    parts.add(line)

    val englishOnly = line
        .replace(Regex("[^A-Za-z0-9+().,/\\-\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (englishOnly.length >= 3 && englishOnly != line) {
        parts.add(englishOnly)
    }

    return parts.filter { it.isNotBlank() }
}

private fun isBrandCandidate(line: String): Boolean {
    val lowered = line.lowercase()
    val latinLetters = line.count { it.isLetter() && it.code < 128 }
    val digits = line.count { it.isDigit() }
    return latinLetters >= 3 &&
        digits <= latinLetters + 4 &&
        !lowered.contains("batch") &&
        !lowered.contains("mfg") &&
        !lowered.contains("exp") &&
        !lowered.contains("limited") &&
        !lowered.contains("pharma") &&
        !lowered.contains("pharmaceutical") &&
        !lowered.contains("contains") &&
        !lowered.contains("composition") &&
        !Regex("\\b\\d+(?:\\.\\d+)?\\s?(?:mg|mcg|g|ml)\\b", RegexOption.IGNORE_CASE).containsMatchIn(lowered)
}

private fun brandScore(line: String): Int {
    val lowered = line.lowercase()
    val latinLetters = line.count { it.isLetter() && it.code < 128 }
    val bengaliLetters = line.count { it.code in 0x0980..0x09FF }
    val hasDigits = line.any { it.isDigit() }
    val corrected = correctBrandName(line)

    var score = 0
    score += latinLetters * 2
    score -= bengaliLetters * 3
    if (looksLikeKnownBrand(line)) score += 50
    if (corrected != null && corrected != line.trim()) score += 20
    if (hasDigits) score += 8
    if (Regex("\\b\\d+(?:\\.\\d+)?\\s?(?:mg|mcg|g|ml)\\b", RegexOption.IGNORE_CASE).containsMatchIn(lowered)) {
        score -= 40
    }
    if (line.contains("(") || line.contains(")")) score -= 4
    score -= line.length / 10
    return score
}

private fun isGenericCandidate(line: String): Boolean {
    val lowered = line.lowercase()
    return Regex("\\b\\d+(?:\\.\\d+)?\\s?(?:mg|mcg|g|ml)\\b", RegexOption.IGNORE_CASE).containsMatchIn(lowered) &&
        !lowered.contains("batch") &&
        !lowered.contains("mfg") &&
        !lowered.contains("exp")
}

private fun genericScore(line: String): Int {
    val lowered = line.lowercase()
    val latinLetters = line.count { it.isLetter() && it.code < 128 }
    val bengaliLetters = line.count { it.code in 0x0980..0x09FF }
    val normalized = normalizeToken(lowered)
    val knownTokens = listOf(
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
    )

    var score = 0
    score += latinLetters * 2
    score -= bengaliLetters * 2
    if (knownTokens.any { lowered.contains(it) || normalized.contains(it) }) score += 40
    if (lowered.contains("usp") || lowered.contains("bp")) score += 10
    if (lowered.contains("tablet") || lowered.contains("capsule")) score -= 6
    return score
}
