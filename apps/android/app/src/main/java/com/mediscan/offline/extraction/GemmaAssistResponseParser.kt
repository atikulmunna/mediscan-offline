package com.mediscan.offline.extraction

import com.mediscan.offline.domain.MedicineDraft

class GemmaAssistResponseParser {
    fun parse(rawResponse: String): ExtractionAssistSuggestion? {
        val trimmed = rawResponse.trim()
        if (trimmed.isBlank()) {
            return null
        }

        val brandName = extractString(trimmed, "brand_name")
        val genericName = extractString(trimmed, "generic_name")
        val manufacturer = extractString(trimmed, "manufacturer")
        val strength = extractString(trimmed, "strength")
        val activeIngredients = extractString(trimmed, "active_ingredients")
        val confidence = extractString(trimmed, "confidence") ?: "medium"
        val reviewHints = extractStringArray(trimmed, "review_hints")

        if (
            brandName == null &&
            genericName == null &&
            manufacturer == null &&
            strength == null &&
            activeIngredients == null &&
            reviewHints.isEmpty()
        ) {
            return null
        }

        return ExtractionAssistSuggestion(
            draft = MedicineDraft(
                brandName = brandName,
                genericName = genericName,
                manufacturer = manufacturer,
                strength = strength,
                activeIngredients = activeIngredients,
                confidence = confidence,
            ),
            reviewHints = reviewHints,
            fieldSources = buildMap {
                if (brandName != null) put("brand_name", "Gemma Assist")
                if (genericName != null) put("generic_name", "Gemma Assist")
                if (manufacturer != null) put("manufacturer", "Gemma Assist")
                if (strength != null) put("strength", "Gemma Assist")
                if (activeIngredients != null) put("active_ingredients", "Gemma Assist")
            },
            providerLabel = "gemma-local",
        )
    }

    private fun extractString(source: String, key: String): String? {
        val pattern = Regex(
            """"$key"\s*:\s*(null|"((?:\\.|[^"\\])*)")""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val match = pattern.find(source) ?: return null
        val rawValue = match.groupValues[1]
        if (rawValue.equals("null", ignoreCase = true)) {
            return null
        }
        return unescapeJson(match.groupValues[2]).ifBlank { null }
    }

    private fun extractStringArray(source: String, key: String): List<String> {
        val pattern = Regex(
            """"$key"\s*:\s*\[(.*?)]""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val block = pattern.find(source)?.groupValues?.get(1) ?: return emptyList()
        return Regex(""""((?:\\.|[^"\\])*)"""")
            .findAll(block)
            .map { unescapeJson(it.groupValues[1]).trim() }
            .filter { it.isNotBlank() }
            .toList()
    }

    private fun unescapeJson(value: String): String {
        return value
            .replace("\\n", "\n")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .trim()
    }
}
