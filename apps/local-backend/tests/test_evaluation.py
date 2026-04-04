import tempfile
import unittest
from pathlib import Path

from app.services.evaluation import build_markdown_report, discover_reference_images, summarize_evaluation


class EvaluationServiceTests(unittest.TestCase):
    def test_discover_reference_images_filters_supported_files(self):
        with tempfile.TemporaryDirectory() as temp_dir_name:
            temp_dir = Path(temp_dir_name)
            (temp_dir / "image_1.jpg").write_bytes(b"jpg")
            (temp_dir / "image_2.png").write_bytes(b"png")
            (temp_dir / "notes.txt").write_text("ignore", encoding="utf-8")

            discovered = discover_reference_images(temp_dir)

        self.assertEqual([path.name for path in discovered], ["image_1.jpg", "image_2.png"])

    def test_summarize_evaluation_counts_fields_and_failures(self):
        summary = summarize_evaluation(
            [
                {
                    "status": "ok",
                    "provider": "cloud-llm",
                    "confidence": "high",
                    "extracted": {"brand_name": "Panadol", "batch_number": None, "confidence": "high"},
                },
                {
                    "status": "error",
                    "error": "ocr failed",
                },
            ]
        )

        self.assertEqual(summary["total_images"], 2)
        self.assertEqual(summary["processed_images"], 1)
        self.assertEqual(summary["failed_images"], 1)
        self.assertEqual(summary["provider_counts"]["cloud-llm"], 1)
        self.assertEqual(summary["field_coverage"]["brand_name"]["present"], 1)
        self.assertEqual(summary["field_coverage"]["batch_number"]["missing"], 1)

    def test_build_markdown_report_includes_key_sections(self):
        markdown = build_markdown_report(
            {
                "generated_at": "2026-04-02T00:00:00+00:00",
                "reference_dir": "references/raw",
                "summary": {
                    "total_images": 1,
                    "processed_images": 1,
                    "failed_images": 0,
                    "provider_counts": {"cloud-rules": 1},
                    "confidence_counts": {"medium": 1},
                    "field_coverage": {"brand_name": {"present": 1, "missing": 0}},
                },
                "results": [
                    {
                        "filename": "image_1.jpg",
                        "status": "ok",
                        "provider": "cloud-rules",
                        "confidence": "medium",
                        "review_hints": ["Verify batch number"],
                        "extracted": {"brand_name": "Panadol"},
                    }
                ],
            }
        )

        self.assertIn("# Reference Evaluation Report", markdown)
        self.assertIn("## Provider Counts", markdown)
        self.assertIn("### image_1.jpg", markdown)
        self.assertIn("Panadol", markdown)


if __name__ == "__main__":
    unittest.main()
