import tempfile
import unittest
import zipfile
from base64 import b64encode
from pathlib import Path
from unittest.mock import patch

from app.db import database
from app.services import repository


class RepositoryAuditTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.sqlite_path = Path(self.temp_dir.name) / "test-medicines.db"
        self.scans_dir = Path(self.temp_dir.name) / "scans"
        self.backups_dir = Path(self.temp_dir.name) / "backups"

        self.path_patcher = patch.object(database, "SQLITE_PATH", self.sqlite_path)
        self.scan_patcher = patch.object(database, "SCANS_DIR", self.scans_dir)
        self.repo_path_patcher = patch.object(repository, "SQLITE_PATH", self.sqlite_path)
        self.repo_scan_patcher = patch.object(repository, "SCANS_DIR", self.scans_dir)
        self.repo_backup_patcher = patch.object(repository, "BACKUPS_DIR", self.backups_dir)
        self.path_patcher.start()
        self.scan_patcher.start()
        self.repo_path_patcher.start()
        self.repo_scan_patcher.start()
        self.repo_backup_patcher.start()
        database.init_sqlite()

    def tearDown(self):
        self.path_patcher.stop()
        self.scan_patcher.stop()
        self.repo_path_patcher.stop()
        self.repo_scan_patcher.stop()
        self.repo_backup_patcher.stop()
        self.temp_dir.cleanup()

    def test_insert_update_delete_write_audit_history(self):
        created = repository.sqlite_insert(
            {
                "scanned_at": "2026-02-04T00:00:00+00:00",
                "brand_name": "Panadol",
                "generic_name": "Paracetamol",
                "manufacturer": "GSK",
                "image_filename": "front.jpg",
                "image_path": "scans/front.jpg",
                "raw_extracted": "{}",
                "confidence": "medium",
                "sync_status": "local_only",
                "last_synced_at": None,
                "updated_at": "2026-02-04T00:00:00+00:00",
            }
        )
        created_history = repository.get_medicine_history(created["id"])
        self.assertEqual(len(created_history), 1)
        self.assertEqual(created_history[0]["action"], "create")

        updated = repository.sqlite_update(
            created["id"],
            {"brand_name": "Panadol Extra", "note": "Corrected pack name"},
        )
        self.assertEqual(updated["record"]["brand_name"], "Panadol Extra")
        self.assertEqual(updated["history_entry"]["action"], "update")
        self.assertEqual(updated["history_entry"]["note"], "Corrected pack name")
        self.assertEqual(
            updated["history_entry"]["changed_fields"]["brand_name"],
            {"from": "Panadol", "to": "Panadol Extra"},
        )

        deleted = repository.sqlite_delete(created["id"], note="Duplicate record")
        self.assertEqual(deleted["record"]["brand_name"], "Panadol Extra")
        self.assertEqual(deleted["history_entry"]["action"], "delete")

        history = repository.get_medicine_history(created["id"])
        self.assertEqual([entry["action"] for entry in history], ["delete", "update", "create"])

    def test_export_import_and_backup_helpers(self):
        repository.sqlite_insert(
            {
                "scanned_at": "2026-02-04T00:00:00+00:00",
                "brand_name": "Napa",
                "generic_name": "Paracetamol",
                "manufacturer": "Beximco",
                "image_filename": "front.jpg",
                "image_path": "scans/front.jpg",
                "raw_extracted": "{}",
                "confidence": "medium",
                "sync_status": "local_only",
                "last_synced_at": None,
                "updated_at": "2026-02-04T00:00:00+00:00",
            }
        )

        exported = repository.export_records_payload()
        self.assertEqual(exported["record_count"], 1)
        self.assertEqual(exported["records"][0]["brand_name"], "Napa")

        csv_data = repository.export_records_csv()
        self.assertIn("brand_name", csv_data)
        self.assertIn("Napa", csv_data)

        imported = repository.import_records(
            [
                {
                    "brand_name": "Ace",
                    "generic_name": "Paracetamol",
                    "manufacturer": "Square",
                    "image_filename": "ace.jpg",
                    "image_path": "scans/ace.jpg",
                }
            ],
            skip_possible_duplicates=False,
            note="Imported from test",
        )
        self.assertEqual(len(imported["imported"]), 1)
        imported_record = imported["imported"][0]["record"]
        imported_history = repository.get_medicine_history(imported_record["id"])
        self.assertEqual(imported_history[0]["note"], "Imported from test")

        backup = repository.create_sqlite_backup()
        self.assertTrue(Path(backup["path"]).exists())
        self.assertGreater(backup["size_bytes"], 0)

    def test_bundle_backup_and_restore_helpers(self):
        repository.sqlite_insert(
            {
                "scanned_at": "2026-02-04T00:00:00+00:00",
                "brand_name": "Seclo",
                "generic_name": "Omeprazole",
                "manufacturer": "Square",
                "image_filename": "front.jpg",
                "image_path": "scans/front.jpg",
                "raw_extracted": "{}",
                "confidence": "medium",
                "sync_status": "local_only",
                "last_synced_at": None,
                "updated_at": "2026-02-04T00:00:00+00:00",
            }
        )
        self.scans_dir.mkdir(parents=True, exist_ok=True)
        (self.scans_dir / "front.jpg").write_bytes(b"image-bytes")

        bundle = repository.create_bundle_backup()
        bundle_path = Path(bundle["path"])
        self.assertTrue(bundle_path.exists())

        with zipfile.ZipFile(bundle_path, "r") as archive:
            self.assertIn("medicines.db", archive.namelist())
            self.assertIn("scans/front.jpg", archive.namelist())

        (self.scans_dir / "front.jpg").unlink()
        imported_bundle = b64encode(bundle_path.read_bytes()).decode("ascii")
        restored = repository.restore_bundle_backup(imported_bundle, "bundle.zip")
        self.assertGreaterEqual(restored["records"], 1)
        self.assertEqual(restored["scan_files"], 1)
        self.assertTrue((self.scans_dir / "front.jpg").exists())


if __name__ == "__main__":
    unittest.main()
