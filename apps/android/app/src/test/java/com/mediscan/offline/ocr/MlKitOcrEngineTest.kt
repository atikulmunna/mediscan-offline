package com.mediscan.offline.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class MlKitOcrEngineTest {
    @Test
    fun `mergeRecognizedTexts keeps unique non blank lines in first seen order`() {
        val merged = mergeRecognizedTexts(
            listOf(
                "Batch No.\nSK02556\n",
                "\nMfg. Date\nOCT. 25\nBatch No.\nSK02556",
                "Exp. Date\nSEP. 28",
            ),
        )

        assertEquals(
            """
                Batch No.
                SK02556
                Mfg. Date
                OCT. 25
                Exp. Date
                SEP. 28
            """.trimIndent(),
            merged,
        )
    }
}
