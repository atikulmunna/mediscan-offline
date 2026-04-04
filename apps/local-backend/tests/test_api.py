import json
import unittest
from base64 import b64encode
from unittest.mock import AsyncMock, patch

from fastapi.testclient import TestClient

from app.api.routes import app


class MedicineApiTests(unittest.TestCase):
    def setUp(self):
        self.client = TestClient(app)

    def test_scan_accepts_multiple_images_and_returns_combined_draft(self):
        payload = {
            "images": [
                {"image_base64": "data:image/jpeg;base64,AAAA", "image_filename": "front.jpg", "panel_name": "Front"},
                {
                    "image_base64": "data:image/jpeg;base64,BBBB",
                    "image_filename": "back.jpg",
                    "panel_name": "Back",
                    "panel_type": "packet_date_side",
                },
            ]
        }

        with patch("app.api.routes.run_ocr_on_image") as mock_ocr, patch(
            "app.api.routes.extract_structured_fields", new_callable=AsyncMock
        ) as mock_extract, patch("app.api.routes.save_images_locally") as mock_save, patch(
            "app.api.routes.find_duplicate_candidates"
        ) as mock_duplicates:
            mock_ocr.side_effect = [
                {
                    "text": "Panadol\nParacetamol 500 mg",
                    "lines": [{"text": "Panadol", "score": 0.99}],
                    "panel_type": "strip",
                    "preprocessing": {
                        "original_size": {"width": 800, "height": 600},
                        "processed_size": {"width": 1600, "height": 1200},
                        "steps": ["grayscale", "autocontrast", "upscale_2.0x", "sharpen"],
                    },
                },
                {
                    "text": "Batch BX1\nExp 01/2027",
                    "lines": [{"text": "Batch BX1", "score": 0.98}],
                    "panel_type": "packet_date_side",
                    "preprocessing": {
                        "original_size": {"width": 700, "height": 500},
                        "processed_size": {"width": 1680, "height": 1200},
                        "steps": ["grayscale", "autocontrast", "upscale_2.4x", "sharpen"],
                    },
                },
            ]
            mock_extract.return_value = {
                "extracted": {
                    "brand_name": "Panadol",
                    "generic_name": "Paracetamol",
                    "strength": "500 mg",
                    "batch_number": "BX1",
                    "expiry_date": "01/2027",
                    "confidence": "high",
                },
                "provider": "cloud-rules",
                "field_meta": {
                    "batch_number": {"needs_review": True, "evidence": "Batch BX1"},
                },
                "review_hints": ["Verify batch number: BX1"],
            }
            mock_save.return_value = [
                {"panel_name": "Front", "image_filename": "front-saved.jpg", "image_path": "scans/front-saved.jpg"},
                {
                    "panel_name": "Back",
                    "panel_type": "packet_date_side",
                    "panel_type_label": "Packet Date Side",
                    "image_filename": "back-saved.jpg",
                    "image_path": "scans/back-saved.jpg",
                },
            ]
            mock_duplicates.return_value = {
                "has_possible_duplicates": True,
                "candidates": [{"id": 3, "brand_name": "Panadol", "score": 95, "reasons": ["same brand and batch"]}],
            }

            response = self.client.post("/scan", json=payload)

        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["extracted"]["brand_name"], "Panadol")
        self.assertEqual(data["extraction_provider"], "cloud-rules")
        self.assertEqual(len(data["images"]), 2)
        self.assertIn("[Front]", data["ocr"]["text"])
        self.assertIn("[Back]", data["ocr"]["text"])
        self.assertEqual(data["ocr"]["preprocessing"][1]["panel_type"], "packet_date_side")
        self.assertEqual(len(data["ocr"]["preprocessing"]), 2)
        self.assertIn("steps", data["ocr"]["preprocessing"][0])
        self.assertTrue(data["duplicates"]["has_possible_duplicates"])
        raw_extracted = json.loads(data["draft"]["raw_extracted"])
        self.assertEqual(len(raw_extracted["images"]), 2)
        self.assertEqual(raw_extracted["provider"], "cloud-rules")
        self.assertEqual(data["draft"]["batch_number"], "BX1")
        self.assertEqual(data["field_meta"]["batch_number"]["source_panel_type"], "packet_date_side")

    def test_scan_preserves_cloud_brand_and_generic_when_panel_override_is_weaker(self):
        payload = {
            "images": [
                {
                    "image_base64": "data:image/jpeg;base64,AAAA",
                    "image_filename": "strip.jpg",
                    "panel_name": "Strip",
                    "panel_type": "strip",
                }
            ]
        }

        with patch("app.api.routes.run_ocr_on_image") as mock_ocr, patch(
            "app.api.routes.extract_structured_fields", new_callable=AsyncMock
        ) as mock_extract, patch("app.api.routes.save_images_locally") as mock_save, patch(
            "app.api.routes.find_duplicate_candidates"
        ) as mock_duplicates:
            mock_ocr.return_value = {
                "text": "BEXIMCO\nFilmet400\nMetronidazole 400 mg",
                "lines": [
                    {"text": "BEXIMCO", "score": 0.99},
                    {"text": "Filmet400", "score": 0.96},
                    {"text": "Metronidazole 400 mg", "score": 0.98},
                ],
                "panel_type": "strip",
                "preprocessing": {
                    "original_size": {"width": 800, "height": 600},
                    "processed_size": {"width": 1600, "height": 1200},
                    "steps": ["grayscale", "autocontrast", "upscale_2.0x", "sharpen"],
                },
            }
            mock_extract.return_value = {
                "extracted": {
                    "brand_name": "Filmet400",
                    "generic_name": "Metronidazole 400 mg",
                    "strength": "400 mg",
                    "confidence": "medium",
                },
                "provider": "cloud-rules",
                "field_meta": {
                    "brand_name": {"needs_review": False, "evidence": "Filmet400"},
                    "generic_name": {"needs_review": False, "evidence": "Metronidazole 400 mg"},
                },
                "review_hints": [],
            }
            mock_save.return_value = [
                {
                    "panel_name": "Strip",
                    "panel_type": "strip",
                    "panel_type_label": "Strip",
                    "image_filename": "strip-saved.jpg",
                    "image_path": "scans/strip-saved.jpg",
                }
            ]
            mock_duplicates.return_value = {"has_possible_duplicates": False, "candidates": []}

            response = self.client.post("/scan", json=payload)

        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["draft"]["brand_name"], "Filmet400")
        self.assertEqual(data["draft"]["generic_name"], "Metronidazole 400 mg")

    def test_scan_requires_at_least_one_image(self):
        response = self.client.post("/scan", json={})
        self.assertEqual(response.status_code, 400)
        self.assertIn("Provide at least one image", response.json()["detail"])

    def test_create_medicine_returns_saved_record(self):
        payload = {
            "brand_name": "Panadol",
            "generic_name": "Paracetamol",
            "image_filename": "front.jpg",
            "image_path": "scans/front.jpg",
            "raw_extracted": "{}",
            "confidence": "medium",
        }

        with patch("app.api.routes.sqlite_insert") as mock_insert, patch(
            "app.api.routes.sqlite_get"
        ) as mock_get, patch("app.api.routes.run_sync") as mock_sync, patch(
            "app.api.routes.find_duplicate_candidates"
        ) as mock_duplicates:
            mock_insert.return_value = {
                "id": 7,
                "brand_name": "Panadol",
                "generic_name": "Paracetamol",
                "image_filename": "front.jpg",
                "image_path": "scans/front.jpg",
                "confidence": "medium",
                "sync_status": "local_only",
            }
            mock_get.return_value = mock_insert.return_value
            mock_sync.return_value = None
            mock_duplicates.return_value = {
                "has_possible_duplicates": True,
                "candidates": [{"id": 4, "brand_name": "Panadol", "score": 70, "reasons": ["same brand and strength"]}],
            }

            response = self.client.post("/medicines", json=payload)

        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["id"], 7)
        self.assertEqual(data["record"]["brand_name"], "Panadol")
        self.assertTrue(data["duplicates"]["has_possible_duplicates"])

    def test_update_medicine_returns_record_history_and_duplicates(self):
        payload = {"brand_name": "Updated Panadol", "note": "Corrected brand spelling"}

        updated_record = {
            "id": 7,
            "brand_name": "Updated Panadol",
            "generic_name": "Paracetamol",
            "image_filename": "front.jpg",
            "image_path": "scans/front.jpg",
            "confidence": "medium",
            "sync_status": "local_only",
        }
        history_entry = {
            "id": 12,
            "medicine_id": 7,
            "action": "update",
            "changed_fields": {"brand_name": {"from": "Panadol", "to": "Updated Panadol"}},
            "note": "Corrected brand spelling",
            "created_at": "2026-02-04T00:00:00+00:00",
        }

        with patch("app.api.routes.sqlite_update") as mock_update, patch(
            "app.api.routes.run_sync"
        ) as mock_sync, patch("app.api.routes.find_duplicate_candidates") as mock_duplicates:
            mock_update.return_value = {"record": updated_record, "history_entry": history_entry}
            mock_sync.return_value = None
            mock_duplicates.return_value = {
                "has_possible_duplicates": False,
                "candidates": [],
            }

            response = self.client.patch("/medicines/7", json=payload)

        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertTrue(data["updated"])
        self.assertEqual(data["record"]["brand_name"], "Updated Panadol")
        self.assertEqual(data["history_entry"]["action"], "update")
        self.assertEqual(data["history_entry"]["note"], "Corrected brand spelling")

    def test_get_medicine_history_returns_entries(self):
        with patch("app.api.routes.sqlite_get") as mock_get, patch(
            "app.api.routes.get_medicine_history"
        ) as mock_history:
            mock_get.return_value = {"id": 7, "brand_name": "Panadol"}
            mock_history.return_value = [
                {
                    "id": 12,
                    "medicine_id": 7,
                    "action": "update",
                    "changed_fields": {"brand_name": {"from": "Panadol", "to": "Updated Panadol"}},
                    "note": "Corrected brand spelling",
                    "created_at": "2026-02-04T00:00:00+00:00",
                }
            ]

            response = self.client.get("/medicines/7/history")

        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["medicine_id"], 7)
        self.assertEqual(len(data["history"]), 1)
        self.assertEqual(data["history"][0]["action"], "update")

    def test_export_json_returns_payload(self):
        with patch("app.api.routes.export_records_payload") as mock_export:
            mock_export.return_value = {
                "exported_at": "2026-02-04T00:00:00+00:00",
                "record_count": 1,
                "records": [{"id": 7, "brand_name": "Panadol"}],
                "audit_log": [],
            }
            response = self.client.get("/export/json")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["record_count"], 1)

    def test_import_json_returns_counts(self):
        payload = {
            "records": [
                {
                    "brand_name": "Panadol",
                    "generic_name": "Paracetamol",
                    "image_filename": "front.jpg",
                    "image_path": "scans/front.jpg",
                }
            ],
            "skip_possible_duplicates": True,
            "note": "Imported from test",
        }
        with patch("app.api.routes.import_records") as mock_import, patch(
            "app.api.routes.run_sync"
        ) as mock_sync:
            mock_import.return_value = {
                "imported": [{"record": {"id": 5, "brand_name": "Panadol"}, "duplicates": {"has_possible_duplicates": False, "candidates": []}}],
                "skipped": [],
            }
            mock_sync.return_value = None
            response = self.client.post("/import/json", json=payload)
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["imported_count"], 1)
        self.assertEqual(data["skipped_count"], 0)

    def test_create_backup_returns_metadata(self):
        with patch("app.api.routes.create_sqlite_backup") as mock_backup:
            mock_backup.return_value = {
                "created_at": "2026-02-04T00:00:00+00:00",
                "path": "C:/tmp/backup.db",
                "size_bytes": 1024,
            }
            response = self.client.post("/backup/create")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["size_bytes"], 1024)

    def test_create_bundle_returns_metadata(self):
        with patch("app.api.routes.create_bundle_backup") as mock_bundle:
            mock_bundle.return_value = {
                "created_at": "2026-02-04T00:00:00+00:00",
                "path": "C:/tmp/bundle.zip",
                "size_bytes": 2048,
                "includes_scans": True,
            }
            response = self.client.post("/backup/bundle")
        self.assertEqual(response.status_code, 200)
        self.assertTrue(response.json()["includes_scans"])

    def test_restore_bundle_returns_metadata(self):
        payload = {
            "archive_base64": b64encode(b"fake").decode("ascii"),
            "archive_filename": "bundle.zip",
        }
        with patch("app.api.routes.restore_bundle_backup") as mock_restore:
            mock_restore.return_value = {
                "restored_at": "2026-02-04T00:00:00+00:00",
                "records": 5,
                "scan_files": 12,
            }
            response = self.client.post("/backup/restore", json=payload)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["records"], 5)

    def test_sync_status_returns_stats_and_queue(self):
        with patch("app.api.routes.get_sync_stats") as mock_stats, patch(
            "app.api.routes.list_sync_queue_items"
        ) as mock_queue:
            mock_stats.return_value = {"enabled": True, "pending": 1, "failed": 0}
            mock_queue.return_value = [{"id": 1, "status": "pending", "operation": "upsert"}]
            response = self.client.get("/sync/status")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["stats"]["pending"], 1)
        self.assertEqual(len(data["queue"]), 1)


if __name__ == "__main__":
    unittest.main()
