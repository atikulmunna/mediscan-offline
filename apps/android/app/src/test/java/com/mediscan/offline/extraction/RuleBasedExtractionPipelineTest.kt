package com.mediscan.offline.extraction

import com.mediscan.offline.domain.CapturePanelType
import com.mediscan.offline.domain.CapturedPanel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleBasedExtractionPipelineTest {
    private val pipeline = RuleBasedExtractionPipeline()

    @Test
    fun `extract normalizes noisy strip brand and generic`() = runBlocking {
        val panels = listOf(
            CapturedPanel(
                localUri = "file://strip.jpg",
                panelType = CapturePanelType.Strip,
                panelName = "Strip",
                ocrText = """
                    Naprosy
                    08204064 RAAEr
                    Naprosyn'soN
                    Naprosyn500
                    Naproxen USP 500 mg
                    Naprosyn500
                    Limited BSIC Tong, Gazipur, Bangladesh
                """.trimIndent(),
            ),
        )

        val result = pipeline.extract(panels)

        assertEquals("Naprosyn 500", result.draft.brandName)
        assertEquals("Naproxen USP 500 mg", result.draft.genericName)
        assertEquals("500 mg", result.draft.strength)
        assertEquals("Limited BSIC Tongi, Gazipur, Bangladesh", result.draft.manufacturer)
        assertEquals("Strip", result.fieldSources["brand_name"])
    }

    @Test
    fun `extract prefers packet date side for operational fields`() = runBlocking {
        val panels = listOf(
            CapturedPanel(
                localUri = "file://date.jpg",
                panelType = CapturePanelType.PacketDateSide,
                panelName = "Packet Date Side",
                ocrText = """
                    Batch: AB1234
                    MFG: 01/2026
                    EXP: 01/2029
                    MA No: DG-77
                """.trimIndent(),
            ),
            CapturedPanel(
                localUri = "file://detail.jpg",
                panelType = CapturePanelType.PacketDetailSide,
                panelName = "Packet Detail Side",
                ocrText = """
                    Naprosyn 500
                    Naproxen USP 500 mg
                    10 tablets
                """.trimIndent(),
            ),
        )

        val result = pipeline.extract(panels)

        assertEquals("AB1234", result.draft.batchNumber)
        assertEquals("01/2026", result.draft.manufactureDate)
        assertEquals("01/2029", result.draft.expiryDate)
        assertEquals("MA No: DG-77", result.draft.licenseNumber)
        assertEquals("10 tablets", result.draft.quantity)
        assertEquals("Packet Date Side", result.fieldSources["batch_number"])
    }

    @Test
    fun `extract emits review hints when packet date fields are missing`() = runBlocking {
        val panels = listOf(
            CapturedPanel(
                localUri = "file://strip.jpg",
                panelType = CapturePanelType.Strip,
                panelName = "Strip",
                ocrText = "Naprosyn 500\nNaproxen USP 500 mg",
            ),
        )

        val result = pipeline.extract(panels)

        assertTrue(result.reviewHints.any { it.contains("Batch number missing") })
        assertTrue(result.reviewHints.any { it.contains("Expiry date missing") })
    }

    @Test
    fun `extract prefers english medicine lines when bengali and english are mixed`() = runBlocking {
        val panels = listOf(
            CapturedPanel(
                localUri = "file://detail.jpg",
                panelType = CapturePanelType.PacketDetailSide,
                panelName = "Packet Detail Side",
                ocrText = """
                    ন্যাপ্রোসিন ৫০০
                    Naprosyn 500
                    ন্যাপ্রোক্সেন ইউএসপি ৫০০ মি.গ্রা.
                    Naproxen USP 500 mg
                """.trimIndent(),
            ),
        )

        val result = pipeline.extract(panels)

        assertEquals("Naprosyn 500", result.draft.brandName)
        assertEquals("Naproxen USP 500 mg", result.draft.genericName)
        assertEquals("Packet Detail Side", result.fieldSources["brand_name"])
        assertEquals("Packet Detail Side", result.fieldSources["generic_name"])
    }

    @Test
    fun `extract ignores manufacturer line when brand and generic are also present`() = runBlocking {
        val panels = listOf(
            CapturedPanel(
                localUri = "file://strip.jpg",
                panelType = CapturePanelType.Strip,
                panelName = "Strip",
                ocrText = """
                    Radiant Pharmaceuticals Limited
                    ন্যাপ্রোসিন
                    Naprosyn 500
                    Naproxen USP 500 mg
                """.trimIndent(),
            ),
        )

        val result = pipeline.extract(panels)

        assertEquals("Naprosyn 500", result.draft.brandName)
        assertEquals("Naproxen USP 500 mg", result.draft.genericName)
        assertEquals("Radiant Pharmaceuticals Limited", result.draft.manufacturer)
    }

    @Test
    fun `extract parses labeled packet operational fields and avoids address as brand`() = runBlocking {
        val panels = listOf(
            CapturedPanel(
                localUri = "file://packet.jpg",
                panelType = CapturePanelType.PacketDetailSide,
                panelName = "Packet Detail Side",
                ocrText = """
                    Prescription only
                    Batch No. : SK02556
                    Mfg. Date : OCT. 25
                    Exp. Date : SEP. 28
                    Mfg. Lic. No. : 33 & 114
                    MA No. : 012-350-021
                    Manufactured by
                    Apex Pharma Limited
                    Shafipur, Kaliakair, Gazipur
                    Manufactured for
                    Square Pharmaceuticals PLC.
                """.trimIndent(),
            ),
        )

        val result = pipeline.extract(panels)

        assertEquals("SK02556", result.draft.batchNumber)
        assertEquals("OCT. 25", result.draft.manufactureDate)
        assertEquals("SEP. 28", result.draft.expiryDate)
        assertEquals("Mfg Lic: 33 & 114; MA No: 012-350-021", result.draft.licenseNumber)
        assertEquals("Apex Pharmaceuticals Limited", result.draft.manufacturer)
        assertEquals(null, result.draft.brandName)
    }

    @Test
    fun `extract parses split packet date labels without leaking ma number into mfg`() = runBlocking {
        val panels = listOf(
            CapturedPanel(
                localUri = "file://packet-split.jpg",
                panelType = CapturePanelType.PacketDateSide,
                panelName = "Packet Date Side",
                ocrText = """
                    Prescription only
                    Batch No. :
                    SK02556
                    Mfg. Date :
                    OCT. 25
                    Exp. Date :
                    SEP. 28
                    Mfg. Lic. No. :
                    33 & 114
                    MA No. :
                    012-350-021
                    Manufactured by
                    Apex Pharma Limited
                    Manufactured for
                    Square Pharmaceuticals PLC.
                """.trimIndent(),
            ),
        )

        val result = pipeline.extract(panels)

        assertEquals("SK02556", result.draft.batchNumber)
        assertEquals("OCT. 25", result.draft.manufactureDate)
        assertEquals("SEP. 28", result.draft.expiryDate)
        assertEquals("Mfg Lic: 33 & 114; MA No: 012-350-021", result.draft.licenseNumber)
        assertEquals("Apex Pharmaceuticals Limited", result.draft.manufacturer)
    }

    @Test
    fun `extract does not treat ma number as manufacture date when actual date is missing`() = runBlocking {
        val panels = listOf(
            CapturedPanel(
                localUri = "file://packet-missing-date.jpg",
                panelType = CapturePanelType.PacketDateSide,
                panelName = "Packet Date Side",
                ocrText = """
                    Batch No. :
                    SK02556
                    Mfg. Date :
                    MA No. : 012-350-021
                    Mfg. Lic. No. :
                    33 & 114
                """.trimIndent(),
            ),
        )

        val result = pipeline.extract(panels)

        assertEquals("SK02556", result.draft.batchNumber)
        assertEquals(null, result.draft.manufactureDate)
        assertEquals("Mfg Lic: 33 & 114; MA No: 012-350-021", result.draft.licenseNumber)
    }

    @Test
    fun `extract does not treat month year as brand when packet detail text is missing`() = runBlocking {
        val panels = listOf(
            CapturedPanel(
                localUri = "file://packet-date-only.jpg",
                panelType = CapturePanelType.PacketDetailSide,
                panelName = "Packet Detail Side",
                ocrText = """
                    OCT 25
                    SEP 28
                    35.00
                """.trimIndent(),
            ),
        )

        val result = pipeline.extract(panels)

        assertEquals(null, result.draft.brandName)
    }

    @Test
    fun `extract parses stacked packet date label block from screenshot layout`() = runBlocking {
        val panels = listOf(
            CapturedPanel(
                localUri = "file://packet-stacked.jpg",
                panelType = CapturePanelType.PacketDateSide,
                panelName = "Packet Date Side",
                ocrText = """
                    Prescription only
                    Batch No. :
                    Mfg. Date :
                    Exp. Date :
                    MRP (Tk.):
                    (including VAT)
                    5K02556
                    OCT 25
                    SEP 28
                    35.00
                    Mfg. Lic. No.: 33 & 114
                    MA No.:012-350-021
                    Manufactured for
                    8'940001'275019
                    Manufactured by
                    Apex Pharma Limited
                """.trimIndent(),
                focusedOcrText = """
                    Prescription only
                    Batch No. :
                    Mfg. Date :
                    Exp. Date :
                    MRP (Tk.):
                    (including VAT)
                    5K02556
                    OCT 25
                    SEP 28
                    35.00
                    Mfg. Lic. No.: 33 & 114
                    MA No.:012-350-021
                """.trimIndent(),
            ),
        )

        val result = pipeline.extract(panels)

        assertEquals("5K02556", result.draft.batchNumber)
        assertEquals("OCT 25", result.draft.manufactureDate)
        assertEquals("SEP 28", result.draft.expiryDate)
        assertEquals("Mfg Lic: 33 & 114; MA No: 012-350-021", result.draft.licenseNumber)
    }

    @Test
    fun `extract recovers emistat strip brand and ondansetron generic from mixed text`() = runBlocking {
        val panels = listOf(
            CapturedPanel(
                localUri = "file://strip.jpg",
                panelType = CapturePanelType.Strip,
                panelName = "Strip",
                ocrText = """
                    Emistat
                    Ondansetron USP
                    8 mg
                    MA NO. 323 3032
                    Healthcare Pharmaceuticals Ltd.
                    এমিস্টাট
                    ওন্ডানসেট্রন ইউএসপি
                """.trimIndent(),
            ),
        )

        val result = pipeline.extract(panels)

        assertEquals("Emistat", result.draft.brandName)
        assertEquals("Ondansetron USP 8 mg", result.draft.genericName)
        assertEquals("8 mg", result.draft.strength)
        assertEquals("Healthcare Pharmaceuticals Ltd.", result.draft.manufacturer)
    }
}
