package com.mediscan.offline.extraction

class SampleGemmaResponseProvider {
    suspend fun generate(payload: GemmaAssistPayload): String? {
        val prompt = payload.userPrompt
        val normalizedPrompt = normalizeToken(prompt)

        val brand = when {
            normalizedPrompt.contains("naprosyn") || normalizedPrompt.contains("naprosy") -> "Naprosyn 500"
            normalizedPrompt.contains("napaextra") ||
                (normalizedPrompt.contains("napa") && normalizedPrompt.contains("extra")) ||
                normalizedPrompt.contains("iapaextra") -> "Napa Extra"
            normalizedPrompt.contains("napaone") -> "NapaOne"
            normalizedPrompt.contains("trilock10") || normalizedPrompt.contains("trilock") -> "Trilock10"
            normalizedPrompt.contains("norium10") || normalizedPrompt.contains("norium") -> "Norium 10"
            normalizedPrompt.contains("filfresh") -> "Filfresh"
            normalizedPrompt.contains("filmet400") || normalizedPrompt.contains("filmet") -> "Filmet400"
            normalizedPrompt.contains("emistat") -> "Emistat"
            else -> null
        }

        val generic = when {
            normalizedPrompt.contains("naproxen") -> "Naproxen USP 500 mg"
            brand == "Napa Extra" -> "Paracetamol 500 mg + Caffeine 65 mg"
            normalizedPrompt.contains("paracetamol") && normalizedPrompt.contains("caffeine") -> "Paracetamol 500 mg + Caffeine 65 mg"
            normalizedPrompt.contains("paracetamol") -> "Paracetamol"
            normalizedPrompt.contains("montelukast") -> "Montelukast USP 10 mg"
            normalizedPrompt.contains("flunarizine") -> "Flunarizine 10 mg"
            normalizedPrompt.contains("metronidazole") -> "Metronidazole 400 mg"
            normalizedPrompt.contains("melatonin") -> "Melatonin 3 mg"
            normalizedPrompt.contains("ondansetron") -> "Ondansetron USP 8 mg"
            else -> null
        }

        val strength = when {
            brand == "Napa Extra" -> "500 mg + 65 mg"
            brand == "Emistat" -> "8 mg"
            else -> detectStrengthFromPrompt(prompt)
        }
        val manufacturer = when {
            normalizedPrompt.contains("apexpharmalimited") -> "Apex Pharma Limited"
            normalizedPrompt.contains("apexpharmaceuticals") -> "Apex Pharmaceuticals Limited"
            normalizedPrompt.contains("radiantpharmaceuticals") ||
                normalizedPrompt.contains("adiantphatmaceuticals") ||
                normalizedPrompt.contains("adiantpharmaceuticals") -> "Radiant Pharmaceuticals Limited"
            normalizedPrompt.contains("squarepharmaceuticals") -> "Square Pharmaceuticals Limited"
            normalizedPrompt.contains("inceptapharmaceuticals") -> "Incepta Pharmaceuticals Limited"
            normalizedPrompt.contains("healthcarepharmaceuticalsltd") -> "Healthcare Pharmaceuticals Ltd."
            else -> null
        }

        if (brand == null && generic == null && manufacturer == null && strength == null) {
            return null
        }

        val hints = buildList {
            if (brand != null) add("Brand candidate was recovered from mixed OCR text.")
            if (generic != null) add("Generic line was normalized from bilingual or noisy OCR.")
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
        val matches = Regex("\\b\\d+(?:\\.\\d+)?\\s?(?:mg|mcg|g|ml)\\b", RegexOption.IGNORE_CASE)
            .findAll(prompt)
            .map { it.value.replace(Regex("\\s+"), " ").trim() }
            .distinct()
            .toList()

        return when {
            matches.isEmpty() -> null
            matches.size == 1 -> matches.first()
            else -> matches.joinToString(" + ")
        }
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
