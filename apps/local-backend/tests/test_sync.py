import unittest
from unittest.mock import AsyncMock, patch

from app.services.sync import run_sync


class SyncServiceTests(unittest.IsolatedAsyncioTestCase):
    async def test_run_sync_marks_successful_items(self):
        items = [
            {"id": 1, "medicine_id": 7, "operation": "upsert", "payload": '{"local_id": 7}', "attempt_count": 0},
            {"id": 2, "medicine_id": None, "operation": "delete", "payload": '{"local_id": 8}', "attempt_count": 1},
        ]
        with patch("app.services.sync.USE_SUPABASE_SYNC", True), patch(
            "app.services.sync.get_pending_queue_items", return_value=items
        ), patch("app.services.sync.supa_upsert_record", new_callable=AsyncMock) as mock_upsert, patch(
            "app.services.sync.supa_delete_record", new_callable=AsyncMock
        ) as mock_delete, patch("app.services.sync.mark_medicine_synced") as mock_synced, patch(
            "app.services.sync.mark_queue_success"
        ) as mock_success, patch("app.services.sync.mark_queue_failure") as mock_failure, patch(
            "app.services.sync.list_sync_queue_items", return_value=[]
        ):
            result = await run_sync(limit=10, trigger="test")

        self.assertEqual(result["processed"], 2)
        self.assertEqual(result["succeeded"], 2)
        self.assertEqual(result["failed"], 0)
        mock_upsert.assert_awaited_once()
        mock_delete.assert_awaited_once()
        mock_synced.assert_called_once_with(7)
        self.assertEqual(mock_success.call_count, 2)
        mock_failure.assert_not_called()

    async def test_run_sync_marks_failed_items(self):
        items = [
            {"id": 3, "medicine_id": 7, "operation": "upsert", "payload": '{"local_id": 7}', "attempt_count": 4},
        ]
        with patch("app.services.sync.USE_SUPABASE_SYNC", True), patch(
            "app.services.sync.SYNC_MAX_ATTEMPTS", 5
        ), patch("app.services.sync.get_pending_queue_items", return_value=items), patch(
            "app.services.sync.supa_upsert_record", new_callable=AsyncMock, side_effect=RuntimeError("boom")
        ), patch("app.services.sync.mark_medicine_synced") as mock_synced, patch(
            "app.services.sync.mark_queue_success"
        ) as mock_success, patch("app.services.sync.mark_queue_failure") as mock_failure, patch(
            "app.services.sync.list_sync_queue_items", return_value=[]
        ):
            result = await run_sync(limit=10, trigger="test")

        self.assertEqual(result["processed"], 1)
        self.assertEqual(result["failed"], 1)
        self.assertEqual(result["dead_letter"], 1)
        mock_failure.assert_called_once()
        mock_success.assert_not_called()
        mock_synced.assert_not_called()


if __name__ == "__main__":
    unittest.main()
