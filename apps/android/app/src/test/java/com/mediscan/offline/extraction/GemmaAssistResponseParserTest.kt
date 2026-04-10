package com.mediscan.offline.extraction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaAssistResponseParserTest {
    private val parser = GemmaAssistResponseParser()

    @Test
    fun `parse extracts structured draft fields and review hints`() {
        val suggestion = parser.parse(
            """
            {
              "brand_name": "Naprosyn 500",
              "generic_name": "Naproxen USP 500 mg",
              "manufacturer": "Radiant Pharmaceuticals Limited",
              "strength": "500 mg",
              "active_ingredients": "Naproxen USP 500 mg",
              "confidence": "medium",
              "review_hints": ["Verify batch from packet date side", "Brand recovered from strip OCR"]
            }
            """.trimIndent(),
        )

        requireNotNull(suggestion)
        assertEquals("Naprosyn 500", suggestion.draft.brandName)
        assertEquals("Naproxen USP 500 mg", suggestion.draft.genericName)
        assertEquals("500 mg", suggestion.draft.strength)
        assertEquals("medium", suggestion.draft.confidence)
        assertEquals("Gemma Assist", suggestion.fieldSources["brand_name"])
        assertTrue(suggestion.reviewHints.any { it.contains("Verify batch") })
    }

    @Test
    fun `parse returns null for empty or unusable responses`() {
        assertNull(parser.parse(""))
        assertNull(parser.parse("""{"brand_name": null, "generic_name": null, "review_hints": []}"""))
    }
}
