package com.mediscan.offline.extraction

import kotlin.math.max

private val brandAliases = mapOf(
    "naprosyn500" to "Naprosyn 500",
    "naprosyn500mg" to "Naprosyn 500",
    "naprosyn" to "Naprosyn 500",
    "naprosy" to "Naprosyn 500",
    "naprosynson" to "Naprosyn 500",
    "napaone" to "NapaOne",
    "napaextra" to "Napa Extra",
    "trilock10" to "Trilock10",
    "norium10" to "Norium 10",
    "filmet400" to "Filmet400",
    "filfresh" to "Filfresh",
    "emistat" to "Emistat",
    "alatrol" to "Alatrol",
)

private val englishBrandVocabulary = listOf(
    "naprosyn",
    "napaone",
    "napa",
    "trilock",
    "norium",
    "filmet",
    "filfresh",
    "emistat",
    "alatrol",
)

private val genericCanonical = mapOf(
    "naproxen" to "Naproxen",
    "paracetamol" to "Paracetamol",
    "esomeprazole" to "Esomeprazole",
    "rupatadine" to "Rupatadine",
    "metronidazole" to "Metronidazole",
    "melatonin" to "Melatonin",
    "flucloxacillin" to "Flucloxacillin",
    "montelukast" to "Montelukast",
    "flunarizine" to "Flunarizine",
    "ondansetron" to "Ondansetron",
    "cetirizine" to "Cetirizine",
    "cranberry" to "Cranberry",
    "calcium" to "Calcium",
    "caffeine" to "Caffeine",
)

private val manufacturerAliases = mapOf(
    "radiantpharmaceuticalslimited" to "Radiant Pharmaceuticals Limited",
    "adiantphatmaceuticalslimited" to "Radiant Pharmaceuticals Limited",
    "adiantpharmaceuticalslimited" to "Radiant Pharmaceuticals Limited",
    "radiantphatmaceuticalslimited" to "Radiant Pharmaceuticals Limited",
    "apexpharmalimited" to "Apex Pharma Limited",
    "apexpharmaceuticalslimited" to "Apex Pharmaceuticals Limited",
    "beximcopharmaceuticalslimited" to "Beximco Pharmaceuticals Limited",
    "squarepharmaceuticalslimited" to "Square Pharmaceuticals Limited",
    "inceptapharmaceuticalslimited" to "Incepta Pharmaceuticals Limited",
)

fun normalizeToken(value: String?): String {
    return value
        .orEmpty()
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "")
}

fun correctBrandName(value: String?): String? {
    if (value.isNullOrBlank()) {
        return null
    }

    val compact = normalizeToken(value)
    brandAliases[compact]?.let { return it }

    var bestMatch: String? = null
    var bestDistance = Int.MAX_VALUE
    for ((alias, canonical) in brandAliases) {
        val distance = levenshtein(compact, alias)
        if (distance < bestDistance) {
            bestDistance = distance
            bestMatch = canonical
        }
    }

    return if (bestMatch != null && bestDistance <= 2) bestMatch else value.trim()
}

fun looksLikeKnownBrand(value: String?): Boolean {
    val compact = normalizeToken(value)
    if (compact.isBlank()) {
        return false
    }
    if (brandAliases.containsKey(compact)) {
        return true
    }
    return englishBrandVocabulary.any { token ->
        compact.contains(token) || levenshtein(compact, token) <= 2
    }
}

fun correctGenericLine(value: String?): String? {
    if (value.isNullOrBlank()) {
        return null
    }

    var corrected = value
        .replace(Regex("(\\d+)\\.m\\b", RegexOption.IGNORE_CASE), "$1 mg")
        .replace(Regex("\\s+"), " ")
        .trim()

    for ((token, canonical) in genericCanonical) {
        corrected = corrected.replace(
            Regex("\\b${Regex.escape(token)}\\b", RegexOption.IGNORE_CASE),
            canonical,
        )
    }

    for ((token, canonical) in genericCanonical) {
        val words = corrected.split(" ")
        val updatedWords = words.map { word ->
            val compact = normalizeToken(word)
            if (compact.isBlank()) {
                word
            } else {
                val distance = levenshtein(compact, token)
                if (distance <= 2 && compact.length >= 5) canonical else word
            }
        }
        corrected = updatedWords.joinToString(" ").replace(Regex("\\s+"), " ").trim()
    }

    corrected = corrected.replace(Regex("""[.,;:]+$"""), "").trim()

    return corrected.ifBlank { value.trim() }
}

fun correctManufacturerName(value: String?): String? {
    if (value.isNullOrBlank()) {
        return null
    }

    val cleaned = value
        .replace(Regex("\\bphatmaceuticals\\b", RegexOption.IGNORE_CASE), "Pharmaceuticals")
        .replace(Regex("\\bradiant\\b", RegexOption.IGNORE_CASE), "Radiant")
        .replace(Regex("\\badiant\\b", RegexOption.IGNORE_CASE), "Radiant")
        .replace(Regex("\\btong\\b", RegexOption.IGNORE_CASE), "Tongi")
        .replace(Regex("\\s+"), " ")
        .trim()

    val compact = normalizeToken(cleaned)
    manufacturerAliases[compact]?.let { canonical ->
        return if (cleaned.contains(",")) {
            val suffix = cleaned.substringAfter(",", "")
            if (suffix.isBlank()) canonical else "$canonical, ${suffix.trim()}"
        } else {
            canonical
        }
    }

    var bestMatch: String? = null
    var bestDistance = Int.MAX_VALUE
    for ((alias, canonical) in manufacturerAliases) {
        val distance = levenshtein(compact, alias)
        if (distance < bestDistance) {
            bestDistance = distance
            bestMatch = canonical
        }
    }

    return if (bestMatch != null && bestDistance <= 4) {
        if (cleaned.contains(",")) {
            val suffix = cleaned.substringAfter(",", "")
            if (suffix.isBlank()) bestMatch else "$bestMatch, ${suffix.trim()}"
        } else {
            bestMatch
        }
    } else {
        cleaned
    }
}

private fun levenshtein(left: String, right: String): Int {
    if (left == right) {
        return 0
    }
    if (left.isEmpty()) {
        return right.length
    }
    if (right.isEmpty()) {
        return left.length
    }

    val costs = IntArray(right.length + 1) { it }
    for (i in 1..left.length) {
        var previousDiagonal = costs[0]
        costs[0] = i
        for (j in 1..right.length) {
            val previousAbove = costs[j]
            val substitutionCost = if (left[i - 1] == right[j - 1]) 0 else 1
            costs[j] = minOf(
                costs[j] + 1,
                costs[j - 1] + 1,
                previousDiagonal + substitutionCost,
            )
            previousDiagonal = previousAbove
        }
    }
    return costs[right.length]
}
