package com.mediscan.offline.extraction

class SampleGemmaResponseProvider {
    suspend fun generate(payload: GemmaAssistPayload): String? {
        val prompt = payload.userPrompt
        val normalizedPrompt = normalizeToken(prompt)

        val brand = when {
            normalizedPrompt.contains("naprosyn") || normalizedPrompt.contains("naprosy") -> "Naprosyn 500"
            normalizedPrompt.contains("napaextra") -> "Napa Extra"
            normalizedPrompt.contains("napaone") -> "NapaOne"
            normalizedPrompt.contains("trilock10") || normalizedPrompt.contains("trilock") -> "Trilock10"
            normalizedPrompt.contains("norium10") || normalizedPrompt.contains("norium") -> "Norium 10"
            normalizedPrompt.contains("filfresh") -> "Filfresh"
            normalizedPrompt.contains("filmet400") || normalizedPrompt.contains("filmet") -> "Filmet400"
            else -> null
        }

        val generic = when {
            normalizedPrompt.contains("naproxen") -> "Naproxen USP 500 mg"
            normalizedPrompt.contains("paracetamol") && normalizedPrompt.contains("caffeine") -> "Paracetamol 500 mg + Caffeine 65 mg"
            normalizedPrompt.contains("paracetamol") -> "Paracetamol"
            normalizedPrompt.contains("montelukast") -> "Montelukast USP 10 mg"
            normalizedPrompt.contains("flunarizine") -> "Flunarizine 10 mg"
            normalizedPrompt.contains("metronidazole") -> "Metronidazole 400 mg"
            normalizedPrompt.contains("melatonin") -> "Melatonin 3 mg"
            else -> null
        }

        val strength = detectStrengthFromPrompt(prompt)
        val manufacturer = when {
            normalizedPrompt.contains("radiantpharmaceuticals") ||
                normalizedPrompt.contains("adiantphatmaceuticals") ||
                normalizedPrompt.contains("adiantpharmaceuticals") -> "Radiant Pharmaceuticals Limited"
            normalizedPrompt.contains("squarepharmaceuticals") -> "Square Pharmaceuticals Limited"
            normalizedPrompt.contains("inceptapharmaceuticals") -> "Incepta Pharmaceuticals Limited"
            else -> null
        }

        if (brand == null && generic == null && manufacturer == null && strength == null) {
            return null
        }

        val hints = buildList {
            if (brand != null) add("Sample local assist recovered a higher-confidence brand candidate.")
            if (generic != null) add("Sample local assist preferred the English generic line from mixed OCR text.")
            add("Sample assist output: replace this provider with a real local Gemma runtime later.")
        }

        return buildString {
            appendLine("{")
            appendLine("""  "brand_name": ${jsonStringOrNull(brand)},""")
            appendLine("""  "generic_name": ${jsonStringOrNull(generic)},""")
            appendLine("""  "manufacturer": ${jsonStringOrNull(manufacturer)},""")
            appendLine("""  "strength": ${jsonStringOrNull(strength)},""")
            appendLine("""  "active_ingredients": ${jsonStringOrNull(generic)},""")
            appendLine("""  "confidence": "medium",""")
            appendLine("""  "review_hints": [${hints.joinToString(", ") { jsonString(it) }}]""")
            append("}")
        }
    }

    private fun detectStrengthFromPrompt(prompt: String): String? {
        return Regex("\\b\\d+(?:\\.\\d+)?\\s?(?:mg|mcg|g|ml)\\b", RegexOption.IGNORE_CASE)
            .find(prompt)
            ?.value
    }

    private fun jsonStringOrNull(value: String?): String {
        return value?.let(::jsonString) ?: "null"
    }

    private fun jsonString(value: String): String {
        return "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n") + "\""
    }
}
