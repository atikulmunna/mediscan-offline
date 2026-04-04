import json

from app.core.config import SYNC_MAX_ATTEMPTS, USE_SUPABASE_SYNC
from app.services.repository import (
    get_pending_queue_items,
    list_sync_queue_items,
    mark_medicine_synced,
    mark_queue_failure,
    mark_queue_success,
)
from app.services.supabase import supa_delete_record, supa_upsert_record


async def run_sync(limit: int = 25, trigger: str = "manual") -> dict:
    if not USE_SUPABASE_SYNC:
        return {"enabled": False, "processed": 0, "succeeded": 0, "failed": 0, "trigger": trigger}

    items = get_pending_queue_items(limit)
    processed = 0
    succeeded = 0
    failed = 0
    dead_letter = 0

    for item in items:
        processed += 1
        try:
            payload = json.loads(item["payload"])
            if item["operation"] == "upsert":
                await supa_upsert_record(payload)
                if item["medicine_id"]:
                    mark_medicine_synced(item["medicine_id"])
            elif item["operation"] == "delete":
                await supa_delete_record(payload["local_id"])
            mark_queue_success(item["id"])
            succeeded += 1
        except Exception as exc:
            mark_queue_failure(item["id"], item.get("attempt_count", 0), str(exc)[:500])
            failed += 1
            if item.get("attempt_count", 0) + 1 >= SYNC_MAX_ATTEMPTS:
                dead_letter += 1

    return {
        "enabled": True,
        "trigger": trigger,
        "processed": processed,
        "succeeded": succeeded,
        "failed": failed,
        "dead_letter": dead_letter,
        "queue_preview": list_sync_queue_items(limit=5),
    }
