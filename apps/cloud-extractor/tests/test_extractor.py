import asyncio
import unittest
from unittest.mock import AsyncMock, patch

from fastapi.testclient import TestClient

from app.extractor import normalize_ocr_text, rule_normalize_ocr_text
from app.main import app


class CloudExtractorTests(unittest.TestCase):
    def setUp(self):
        self.client = TestClient(app)

    def test_normalize_ocr_text_returns_review_metadata(self):
        ocr_text = """Panadol
Paracetamol 500 mg
Batch: BX42
Exp: 01/2027
Manufactured by ACME Pharma Ltd
Store below 25C
"""
        extracted, field_meta, review_hints = rule_normalize_ocr_text(ocr_text)
        self.assertEqual(extracted["brand_name"], "Panadol")
        self.assertEqual(extracted["strength"], "500 mg")
        self.assertTrue(field_meta["batch_number"]["needs_review"])
        self.assertTrue(any("batch number" in hint.lower() for hint in review_hints))

    def test_rule_normalize_ignores_panel_header_lines(self):
        ocr_text = """[image_1]
Napa Extra
Paracetamol 500 mg
"""
        extracted, field_meta, review_hints = rule_normalize_ocr_text(ocr_text)
        self.assertEqual(extracted["brand_name"], "Napa Extra")
        self.assertEqual(extracted["strength"], "500 mg")
        self.assertFalse(any("[image_1]" in hint for hint in review_hints))

    def test_rule_normalize_prefers_repeated_brand_like_line_over_numeric_code(self):
        ocr_text = """[image_3]
1020126E0227
Exium20
Esomeprazole 20 mg
Exium20
Mfg.Lic.66&247
"""
        extracted, _, _ = rule_normalize_ocr_text(ocr_text)
        self.assertEqual(extracted["brand_name"], "Exium20")

    def test_rule_normalize_does_not_treat_manufacturer_alias_as_brand(self):
        ocr_text = """[image_12]
Filfresh
Melatonin 3 mg
SQUARE
Filfresh
"""
        extracted, _, _ = rule_normalize_ocr_text(ocr_text)
        self.assertEqual(extracted["brand_name"], "Filfresh")
        self.assertEqual(extracted["generic_name"], "Melatonin 3 mg")

    def test_rule_normalize_detects_generic_line_beyond_small_keyword_list(self):
        ocr_text = """Rupa
Rupatadine 10 mg
ARISTOPHARMA
"""
        extracted, _, _ = rule_normalize_ocr_text(ocr_text)
        self.assertEqual(extracted["brand_name"], "Rupa")
        self.assertEqual(extracted["generic_name"], "Rupatadine 10 mg")

    def test_rule_normalize_applies_brand_alias_correction(self):
        ocr_text = """NapaoOne
Paracetamol 1000 mg
"""
        extracted, _, _ = rule_normalize_ocr_text(ocr_text)
        self.assertEqual(extracted["brand_name"], "NapaOne")

    def test_rule_normalize_applies_generic_correction_for_ocr_typos(self):
        ocr_text = """Napa Extra
arcetamol 500mg &
"""
        extracted, _, _ = rule_normalize_ocr_text(ocr_text)
        self.assertEqual(extracted["brand_name"], "Napa Extra")
        self.assertEqual(extracted["generic_name"], "Paracetamol 500mg")

    def test_extract_endpoint_returns_structured_response(self):
        response = self.client.post(
            "/extract",
            json={"ocr_text": "Panadol\nParacetamol 500 mg\nBatch: BX42\nExp: 01/2027"},
        )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["provider"], "cloud-rules")
        self.assertIn("extracted", data)
        self.assertIn("field_meta", data)
        self.assertIn("review_hints", data)

    def test_normalize_ocr_text_can_use_llm_provider(self):
        with patch("app.extractor.LLM_ENABLED", True), patch(
            "app.extractor.PROVIDER_MODE", "llm"
        ), patch("app.extractor.llm_normalize_ocr_text", new_callable=AsyncMock) as mock_llm:
            mock_llm.return_value = (
                {"brand_name": "Panadol", "confidence": "high"},
                {"brand_name": {"needs_review": False}},
                [],
                "cloud-llm",
            )
            extracted, field_meta, review_hints, provider = asyncio.run(normalize_ocr_text("Panadol\nParacetamol 500 mg"))
        self.assertEqual(provider, "cloud-llm")
        self.assertEqual(extracted["brand_name"], "Panadol")

    def test_extract_endpoint_falls_back_to_rules_when_llm_fails(self):
        with patch("app.extractor.LLM_ENABLED", True), patch(
            "app.extractor.PROVIDER_MODE", "hybrid"
        ), patch("app.extractor.llm_normalize_ocr_text", new_callable=AsyncMock) as mock_llm:
            mock_llm.side_effect = RuntimeError("llm unavailable")
            response = self.client.post(
                "/extract",
                json={"ocr_text": "Panadol\nParacetamol 500 mg\nBatch: BX42\nExp: 01/2027"},
            )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["provider"], "cloud-rules")
        self.assertEqual(data["extracted"]["brand_name"], "Panadol")


if __name__ == "__main__":
    unittest.main()
