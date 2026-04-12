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
        val packetLines = packetDateLines + packetDetailLines
        val stripLines = groupedLines[CapturePanelType.Strip].orEmpty()
        val allLines = buildList {
            addAll(packetLines)
            addAll(stripLines)
        }
        val allText = allLines.joinToString("\n")

        val brandCandidate = detectBrandName(stripLines, packetDetailLines)
        val genericCandidate = detectGenericName(stripLines, packetDetailLines)
        val manufacturerCandidate = detectManufacturer(allLines)
        val correctedGeneric = correctGenericLine(genericCandidate)
        val draft = MedicineDraft(
            brandName = correctBrandName(brandCandidate),
            genericName = correctedGeneric,
            manufacturer = correctManufacturerName(manufacturerCandidate),
            batchNumber = detectBatch(packetLines),
            strength = detectStrength(
                listOfNotNull(
                    correctedGeneric,
                    genericCandidate,
                    brandCandidate,
                    allText,
                ).joinToString("\n"),
            ),
            quantity = detectQuantity(packetDetailLines.joinToString("\n")),
            manufactureDate = detectManufactureDate(packetLines),
            expiryDate = detectExpiryDate(packetLines),
            licenseNumber = detectLicense(packetLines),
            activeIngredients = correctGenericLine(detectActiveIngredients(allLines)),
            confidence = inferConfidence(
                brandName = correctBrandName(brandCandidate),
                genericName = correctedGeneric,
                batchNumber = detectBatch(packetLines),
                expiryDate = detectExpiryDate(packetLines),
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
): String? {
    val candidateLines = stripLines + packetDetailLines
    return candidateLines
        .filter(::isBrandCandidate)
        .maxByOrNull(::brandScore)
}

private fun detectGenericName(
    stripLines: List<String>,
    packetDetailLines: List<String>,
): String? {
    val candidateLines = stripLines + packetDetailLines
    val combinedCandidate = detectSplitGenericCandidate(candidateLines)
    if (combinedCandidate != null) {
        return combinedCandidate
    }
    return candidateLines
        .filter(::isGenericCandidate)
        .maxByOrNull(::genericScore)
}

private fun detectStrength(text: String): String? {
    val matches = Regex("\\b\\d+(?:\\.\\d+)?\\s?(?:mg|mcg|g|ml)\\b", RegexOption.IGNORE_CASE)
        .findAll(text)
        .map { it.value.replace(Regex("\\s+"), " ").trim() }
        .distinct()
        .toList()

    return when {
        matches.isEmpty() -> null
        matches.size == 1 -> matches.first()
        else -> matches.joinToString(" + ")
    }
}

private fun detectQuantity(text: String): String? {
    return Regex("\\b(\\d+\\s?(?:tablets?|capsules?|strips?|sachets?|ampoules?|vials?))\\b", RegexOption.IGNORE_CASE)
        .find(text)
        ?.groupValues
        ?.get(1)
}

private fun detectBatch(lines: List<String>): String? {
    return extractLabeledValue(
        lines = lines,
        labelPatterns = listOf(
            Regex("""batch\s*(?:no\.?)?""", RegexOption.IGNORE_CASE),
            Regex("""b\.?\s*no\.?""", RegexOption.IGNORE_CASE),
            Regex("""lot""", RegexOption.IGNORE_CASE),
        ),
        isValidValue = { value ->
            value.any { it.isDigit() } && value.any { it.isLetter() } || value.contains("-") || value.contains("/")
        },
    )
}

private fun detectManufactureDate(lines: List<String>): String? {
    return extractLabeledValue(
        lines = lines,
        labelPatterns = listOf(
            Regex("""mfg\.?\s*date""", RegexOption.IGNORE_CASE),
            Regex("""manufacture(?:d)?\s*date""", RegexOption.IGNORE_CASE),
            Regex("""mfg\b""", RegexOption.IGNORE_CASE),
        ),
        isValidValue = ::looksLikeDateValue,
    )
}

private fun detectExpiryDate(lines: List<String>): String? {
    return extractLabeledValue(
        lines = lines,
        labelPatterns = listOf(
            Regex("""exp\.?\s*date""", RegexOption.IGNORE_CASE),
            Regex("""expiry\s*date""", RegexOption.IGNORE_CASE),
            Regex("""expires?""", RegexOption.IGNORE_CASE),
            Regex("""exp\b""", RegexOption.IGNORE_CASE),
        ),
        isValidValue = ::looksLikeDateValue,
    )
}

private fun detectLicense(lines: List<String>): String? {
    val mfgLicense = extractLabeledValue(
        lines = lines,
        labelPatterns = listOf(
            Regex("""mfg\.?\s*lic\.?\s*no\.?""", RegexOption.IGNORE_CASE),
            Regex("""licen[cs]e\s*no\.?""", RegexOption.IGNORE_CASE),
            Regex("""lic\.?\s*no\.?""", RegexOption.IGNORE_CASE),
        ),
        isValidValue = ::looksLikeLicenseValue,
    )
    val maNumber = extractLabeledValue(
        lines = lines,
        labelPatterns = listOf(
            Regex("""ma\s*no\.?""", RegexOption.IGNORE_CASE),
            Regex("""reg(?:istration)?\.?\s*no\.?""", RegexOption.IGNORE_CASE),
        ),
        isValidValue = ::looksLikeLicenseValue,
    )

    return listOfNotNull(
        mfgLicense?.let { "Mfg Lic: $it" },
        maNumber?.let { "MA No: $it" },
    ).takeIf { it.isNotEmpty() }?.joinToString("; ")
}

private fun detectManufacturer(lines: List<String>): String? {
    return lines
        .filter { line ->
            val lowered = line.lowercase()
            val latinWords = Regex("[A-Za-z]{3,}").findAll(line).count()
            latinWords >= 2 &&
                !lowered.contains("ma no") &&
                !lowered.contains("batch") &&
                !lowered.contains("exp") &&
                !lowered.contains("mfg.") &&
                (
                    lowered.contains("limited") ||
                        lowered.contains("ltd") ||
                        lowered.contains("pharma") ||
                        lowered.contains("pharmaceutical") ||
                        lowered.contains("healthcare")
                    )
        }
        .maxByOrNull { line ->
            val lowered = line.lowercase()
            var score = 0
            if (lowered.contains("limited")) score += 6
            if (lowered.contains("pharmaceutical")) score += 5
            if (lowered.contains("healthcare")) score += 4
            score += Regex("[A-Za-z]{3,}").findAll(line).count()
            score
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
        !lowered.contains("prescription") &&
        !lowered.contains("gazipur") &&
        !lowered.contains("bangladesh") &&
        !lowered.contains("kaliakair") &&
        !lowered.contains("shafipur") &&
        !lowered.contains("mrp") &&
        !lowered.contains("vat") &&
        !lowered.contains("barcode") &&
        !lowered.contains("manufactured by") &&
        !lowered.contains("manufactured for") &&
        !lowered.contains("lic") &&
        !lowered.contains("date") &&
        !lowered.contains("ondansetron usp") &&
        !lowered.contains("healthcare pharmaceuticals") &&
        !lowered.contains("ma no") &&
        !lowered.contains("www.") &&
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
    if (lowered.contains(",")) score -= 12
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
        "ondansetron",
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

private fun detectSplitGenericCandidate(lines: List<String>): String? {
    val normalizedLines = lines.map { it.replace(Regex("\\s+"), " ").trim() }
    normalizedLines.forEachIndexed { index, line ->
        val lowered = line.lowercase()
        val hasKnownGenericToken = listOf(
            "naproxen",
            "paracetamol",
            "esomeprazole",
            "rupatadine",
            "metronidazole",
            "melatonin",
            "flucloxacillin",
            "montelukast",
            "flunarizine",
            "ondansetron",
            "caffeine",
            "calcium",
            "cranberry",
        ).any { token ->
            lowered.contains(token) || normalizeToken(lowered).contains(token)
        }

        val looksLikeMedicineNameLine =
            hasKnownGenericToken ||
                lowered.contains(" usp") ||
                lowered.contains(" bp") ||
                lowered.endsWith("usp") ||
                lowered.endsWith("bp")

        if (!looksLikeMedicineNameLine) {
            return@forEachIndexed
        }

        val strengthLine = normalizedLines.getOrNull(index + 1)
            ?.takeIf { Regex("\\b\\d+(?:\\.\\d+)?\\s?(?:mg|mcg|g|ml)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) }

        if (strengthLine != null) {
            return "$line $strengthLine".replace(Regex("\\s+"), " ").trim()
        }
    }

    return null
}

private fun extractLabeledValue(
    lines: List<String>,
    labelPatterns: List<Regex>,
    isValidValue: (String) -> Boolean,
): String? {
    val normalizedLines = lines.map { it.replace(Regex("\\s+"), " ").trim() }
    normalizedLines.forEachIndexed { index, line ->
        val lowered = line.lowercase()
        val labelMatch = labelPatterns.firstOrNull { it.containsMatchIn(lowered) } ?: return@forEachIndexed
        val stripped = line
            .replace(labelMatch, "")
            .replace(Regex("""^[\s:.\-#/]+"""), "")
            .trim()
        val cleaned = cleanExtractedValue(stripped)
        if (!cleaned.isNullOrBlank() && isValidValue(cleaned)) {
            return cleaned
        }

        val nextLine = normalizedLines.getOrNull(index + 1)?.let(::cleanExtractedValue)
        if (!nextLine.isNullOrBlank() && isValidValue(nextLine)) {
            return nextLine
        }
    }
    return null
}

private fun cleanExtractedValue(value: String): String? {
    val cleaned = value
        .replace(Regex("""^[^A-Za-z0-9]+"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
    return cleaned.takeUnless {
        it.isBlank() ||
            it.equals("no", ignoreCase = true) ||
            it.equals("date", ignoreCase = true) ||
            it.equals("for", ignoreCase = true)
    }
}

private fun looksLikeDateValue(value: String): Boolean {
    val lowered = value.lowercase()
    return value.any { it.isDigit() } &&
        (
            Regex("""\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\b""", RegexOption.IGNORE_CASE).containsMatchIn(lowered) ||
                value.contains("/") ||
                value.contains("-")
            )
}

private fun looksLikeLicenseValue(value: String): Boolean {
    return value.any { it.isDigit() } &&
        value.any { it.isLetter() || it.isDigit() } &&
        !value.equals("no", ignoreCase = true)
}
