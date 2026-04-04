import base64
import io
import unittest
from unittest.mock import patch

import app.services.ocr as ocr
from PIL import Image


def make_test_image_b64() -> str:
    image = Image.new("RGB", (32, 32), color="white")
    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    return base64.b64encode(buffer.getvalue()).decode("utf-8")


class OCRServiceTests(unittest.TestCase):
    def setUp(self):
        self.original_engine = ocr.OCR_ENGINE
        self.original_provider = ocr.OCR_PROVIDER
        self.original_error = ocr.OCR_IMPORT_ERROR
        ocr.OCR_ENGINE = None
        ocr.OCR_PROVIDER = None
        ocr.OCR_IMPORT_ERROR = None

    def tearDown(self):
        ocr.OCR_ENGINE = self.original_engine
        ocr.OCR_PROVIDER = self.original_provider
        ocr.OCR_IMPORT_ERROR = self.original_error

    def test_ocr_available_when_any_supported_provider_exists(self):
        with patch("app.services.ocr._provider_available", side_effect=lambda provider: provider == "rapidocr"):
            self.assertTrue(ocr.ocr_available())

    def test_get_ocr_engine_prefers_rapidocr(self):
        fake_engine = object()
        with patch("app.services.ocr._provider_available", side_effect=lambda provider: provider == "rapidocr"), patch(
            "app.services.ocr._build_rapidocr_engine", return_value=fake_engine
        ):
            provider, engine = ocr.get_ocr_engine()

        self.assertEqual(provider, "rapidocr")
        self.assertIs(engine, fake_engine)

    def test_run_ocr_on_image_parses_rapidocr_results(self):
        image_b64 = make_test_image_b64()
        fake_engine = lambda image: (
            [
                [[[0, 0], [1, 0], [1, 1], [0, 1]], "BEXIMCO", 0.99],
                [[[0, 0], [1, 0], [1, 1], [0, 1]], "Paracetamol 500mg", 0.97],
            ],
            [0.1, 0.2, 0.3],
        )

        with patch("app.services.ocr.get_ocr_engine", return_value=("rapidocr", fake_engine)):
            result = ocr.run_ocr_on_image(image_b64)

        self.assertEqual(result["provider"], "rapidocr")
        self.assertIn("BEXIMCO", result["text"])
        self.assertEqual(len(result["lines"]), 2)
        self.assertIn("steps", result["preprocessing"])

    def test_combine_ocr_results_preserves_provider_metadata(self):
        combined = ocr.combine_ocr_results(
            [
                {
                    "panel_name": "Front",
                    "provider": "rapidocr",
                    "text": "BEXIMCO",
                    "lines": [{"text": "BEXIMCO", "score": 0.99}],
                    "preprocessing": {"steps": ["grayscale"]},
                }
            ]
        )

        self.assertEqual(combined["provider"], "rapidocr")
        self.assertEqual(combined["preprocessing"][0]["provider"], "rapidocr")
        self.assertEqual(combined["lines"][0]["panel_name"], "Front")


if __name__ == "__main__":
    unittest.main()
