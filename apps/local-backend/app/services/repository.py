import json
import re
import shutil
import tempfile
import zipfile
from base64 import b64decode
from csv import DictWriter
from io import StringIO
from pathlib import Path

from app.core.config import BACKUPS_DIR, SCANS_DIR, SQLITE_PATH, SYNC_MAX_ATTEMPTS, SYNC_RETRY_DELAY_SECONDS, USE_SUPABASE_SYNC
from app.db.database import get_conn, init_sqlite, utc_now
from app.db.schema import MEDICINE_FIELDS


def queue_sync(conn, medicine_id: int, operation: str, payload: dict):
    now = utc_now()
    conn.execute(
        """
        INSERT INTO sync_queue (medicine_id, operation, payload, status, attempt_count, last_error, next_attempt_at, created_at, updated_at)
        VALUES (?, ?, ?, 'pending', 0, NULL, ?, ?, ?)
        """,
        (medicine_id, operation, json.dumps(payload), now, now, now),
    )


def _to_json(value):
    return json.dumps(value, ensure_ascii=True, sort_keys=True)


def _build_change_map(previous: dict | None, current: dict | None) -> dict:
    keys = set()
    if previous:
        keys.update(previous.keys())
    if current:
        keys.update(current.keys())

    changes = {}
    for key in sorted(keys):
        if key == "id":
            continue
        before = None if previous is None else previous.get(key)
        after = None if current is None else current.get(key)
        if before != after:
            changes[key] = {"from": before, "to": after}
    return changes


def log_audit_event(
    conn,
    medicine_id: int,
    action: str,
    previous_snapshot: dict | None = None,
    next_snapshot: dict | None = None,
    changed_fields: dict | None = None,
    note: str | None = None,
):
    conn.execute(
        """
        INSERT INTO medicine_audit_log (
            medicine_id, action, changed_fields, previous_snapshot, next_snapshot, note, created_at
        )
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        (
            medicine_id,
            action,
            _to_json(changed_fields) if changed_fields is not None else None,
            _to_json(previous_snapshot) if previous_snapshot is not None else None,
            _to_json(next_snapshot) if next_snapshot is not None else None,
            note,
            utc_now(),
        ),
    )
    row = conn.execute("SELECT * FROM medicine_audit_log WHERE id=last_insert_rowid()").fetchone()
    return parse_history_entry(dict(row))


def sqlite_insert(record: dict) -> dict:
    conn = get_conn()
    cols = [column for column in MEDICINE_FIELDS if column in record]
    vals = [record[column] for column in cols]
    cur = conn.execute(
        f"INSERT INTO medicines ({','.join(cols)}) VALUES ({','.join(['?'] * len(cols))})",
        vals,
    )
    row = conn.execute("SELECT * FROM medicines WHERE id=?", (cur.lastrowid,)).fetchone()
    log_audit_event(
        conn,
        cur.lastrowid,
        "create",
        previous_snapshot=None,
        next_snapshot=dict(row),
        changed_fields=_build_change_map(None, dict(row)),
        note="Initial record created",
    )
    conn.commit()
    conn.close()
    return dict(row)


def sqlite_select(search: str = "", limit: int = 50, offset: int = 0):
    conn = get_conn()
    q = f"%{search}%"
    if search:
        rows = conn.execute(
            """
            SELECT * FROM medicines
            WHERE brand_name LIKE ? OR generic_name LIKE ? OR manufacturer LIKE ?
            ORDER BY id DESC LIMIT ? OFFSET ?
            """,
            (q, q, q, limit, offset),
        ).fetchall()
        total = conn.execute(
            """
            SELECT COUNT(*) FROM medicines
            WHERE brand_name LIKE ? OR generic_name LIKE ? OR manufacturer LIKE ?
            """,
            (q, q, q),
        ).fetchone()[0]
    else:
        rows = conn.execute(
            "SELECT * FROM medicines ORDER BY id DESC LIMIT ? OFFSET ?",
            (limit, offset),
        ).fetchall()
        total = conn.execute("SELECT COUNT(*) FROM medicines").fetchone()[0]
    conn.close()
    return [dict(row) for row in rows], total


def sqlite_get(medicine_id: int):
    conn = get_conn()
    row = conn.execute("SELECT * FROM medicines WHERE id=?", (medicine_id,)).fetchone()
    conn.close()
    return dict(row) if row else None


def sqlite_update(medicine_id: int, fields: dict):
    conn = get_conn()
    row = conn.execute("SELECT * FROM medicines WHERE id=?", (medicine_id,)).fetchone()
    if not row:
        conn.close()
        return False
    previous = dict(row)
    note = fields.pop("note", None)
    fields["updated_at"] = utc_now()
    if USE_SUPABASE_SYNC:
        fields["sync_status"] = "pending_sync"
    set_clause = ", ".join(f"{key}=?" for key in fields)
    conn.execute(
        f"UPDATE medicines SET {set_clause} WHERE id=?",
        list(fields.values()) + [medicine_id],
    )
    updated_row = conn.execute("SELECT * FROM medicines WHERE id=?", (medicine_id,)).fetchone()
    updated = dict(updated_row)
    history_entry = log_audit_event(
        conn,
        medicine_id,
        "update",
        previous_snapshot=previous,
        next_snapshot=updated,
        changed_fields=_build_change_map(previous, updated),
        note=note,
    )
    conn.commit()
    conn.close()
    return {"record": updated, "history_entry": history_entry}


def sqlite_delete(medicine_id: int, note: str | None = None):
    conn = get_conn()
    row = conn.execute("SELECT * FROM medicines WHERE id=?", (medicine_id,)).fetchone()
    if not row:
        conn.close()
        return False
    previous = dict(row)
    if USE_SUPABASE_SYNC:
        queue_sync(conn, medicine_id, "delete", {"local_id": medicine_id})
    history_entry = log_audit_event(
        conn,
        medicine_id,
        "delete",
        previous_snapshot=previous,
        next_snapshot=None,
        changed_fields=_build_change_map(previous, None),
        note=note or "Record deleted",
    )
    conn.execute("DELETE FROM medicines WHERE id=?", (medicine_id,))
    conn.commit()
    conn.close()
    return {"record": previous, "history_entry": history_entry}


def get_sync_stats() -> dict:
    conn = get_conn()
    pending = conn.execute("SELECT COUNT(*) FROM sync_queue WHERE status='pending'").fetchone()[0]
    failed = conn.execute("SELECT COUNT(*) FROM sync_queue WHERE status='failed'").fetchone()[0]
    dead_letter = conn.execute("SELECT COUNT(*) FROM sync_queue WHERE status='dead_letter'").fetchone()[0]
    synced = conn.execute("SELECT COUNT(*) FROM medicines WHERE sync_status='synced'").fetchone()[0]
    total = conn.execute("SELECT COUNT(*) FROM medicines").fetchone()[0]
    last_sync = conn.execute(
        "SELECT MAX(last_synced_at) FROM medicines WHERE last_synced_at IS NOT NULL"
    ).fetchone()[0]
    next_attempt = conn.execute(
        """
        SELECT MIN(next_attempt_at) FROM sync_queue
        WHERE status IN ('pending', 'failed') AND next_attempt_at IS NOT NULL
        """
    ).fetchone()[0]
    conn.close()
    return {
        "enabled": USE_SUPABASE_SYNC,
        "pending": pending,
        "failed": failed,
        "dead_letter": dead_letter,
        "synced_records": synced,
        "total_records": total,
        "last_synced_at": last_sync,
        "next_attempt_at": next_attempt,
        "max_attempts": SYNC_MAX_ATTEMPTS,
    }


def get_pending_queue_items(limit: int = 25):
    conn = get_conn()
    now = utc_now()
    items = conn.execute(
        """
        SELECT * FROM sync_queue
        WHERE status IN ('pending', 'failed')
          AND attempt_count < ?
          AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
        ORDER BY created_at ASC
        LIMIT ?
        """,
        (SYNC_MAX_ATTEMPTS, now, limit),
    ).fetchall()
    conn.close()
    return [dict(item) for item in items]


def mark_queue_item(queue_id: int, status: str, last_error=None, next_attempt_at=None):
    conn = get_conn()
    conn.execute(
        """
        UPDATE sync_queue
        SET status=?, attempt_count=attempt_count+1, last_error=?, next_attempt_at=?, updated_at=?
        WHERE id=?
        """,
        (status, last_error, next_attempt_at, utc_now(), queue_id),
    )
    conn.commit()
    conn.close()


def mark_queue_success(queue_id: int):
    mark_queue_item(queue_id, "synced", last_error=None, next_attempt_at=None)


def mark_queue_failure(queue_id: int, attempt_count: int, last_error=None):
    next_status = "dead_letter" if attempt_count + 1 >= SYNC_MAX_ATTEMPTS else "failed"
    next_attempt_at = None
    if next_status != "dead_letter":
        next_attempt_at = utc_now_shifted(SYNC_RETRY_DELAY_SECONDS)
    mark_queue_item(queue_id, next_status, last_error=last_error, next_attempt_at=next_attempt_at)


def utc_now_shifted(seconds: int) -> str:
    from datetime import timedelta

    now = utc_now()
    from datetime import datetime

    shifted = datetime.fromisoformat(now) + timedelta(seconds=seconds)
    return shifted.isoformat()


def list_sync_queue_items(limit: int = 50):
    conn = get_conn()
    rows = conn.execute(
        """
        SELECT * FROM sync_queue
        ORDER BY created_at DESC
        LIMIT ?
        """,
        (limit,),
    ).fetchall()
    conn.close()
    return [dict(row) for row in rows]


def mark_medicine_synced(medicine_id: int):
    conn = get_conn()
    conn.execute(
        "UPDATE medicines SET sync_status='synced', last_synced_at=? WHERE id=?",
        (utc_now(), medicine_id),
    )
    conn.commit()
    conn.close()


def medicine_to_supabase_payload(record: dict) -> dict:
    return {
        "local_id": record["id"],
        "scanned_at": record.get("scanned_at"),
        "brand_name": record.get("brand_name"),
        "generic_name": record.get("generic_name"),
        "manufacturer": record.get("manufacturer"),
        "batch_number": record.get("batch_number"),
        "serial_number": record.get("serial_number"),
        "dosage_form": record.get("dosage_form"),
        "strength": record.get("strength"),
        "quantity": record.get("quantity"),
        "manufacture_date": record.get("manufacture_date"),
        "expiry_date": record.get("expiry_date"),
        "license_number": record.get("license_number"),
        "barcode": record.get("barcode"),
        "indications": record.get("indications"),
        "warnings": record.get("warnings"),
        "storage_info": record.get("storage_info"),
        "active_ingredients": record.get("active_ingredients"),
        "raw_extracted": record.get("raw_extracted"),
        "confidence": record.get("confidence"),
        "image_filename": record.get("image_filename"),
        "image_path": record.get("image_path"),
        "local_updated_at": record.get("updated_at"),
        "last_synced_at": utc_now(),
    }


def queue_upsert_for_record(record: dict):
    conn = get_conn()
    queue_sync(conn, record["id"], "upsert", medicine_to_supabase_payload(record))
    conn.execute(
        "UPDATE medicines SET sync_status='pending_sync' WHERE id=?",
        (record["id"],),
    )
    conn.commit()
    row = conn.execute("SELECT * FROM medicines WHERE id=?", (record["id"],)).fetchone()
    conn.close()
    return dict(row)


def parse_history_entry(entry: dict) -> dict:
    parsed = dict(entry)
    for key in ("changed_fields", "previous_snapshot", "next_snapshot"):
        value = parsed.get(key)
        if not value:
            parsed[key] = {} if key == "changed_fields" else None
            continue
        parsed[key] = json.loads(value)
    return parsed


def get_medicine_history(medicine_id: int, limit: int = 50):
    conn = get_conn()
    rows = conn.execute(
        """
        SELECT * FROM medicine_audit_log
        WHERE medicine_id=?
        ORDER BY id DESC
        LIMIT ?
        """,
        (medicine_id, limit),
    ).fetchall()
    conn.close()
    return [parse_history_entry(dict(row)) for row in rows]


def list_all_medicines():
    conn = get_conn()
    rows = conn.execute("SELECT * FROM medicines ORDER BY id DESC").fetchall()
    conn.close()
    return [dict(row) for row in rows]


def list_all_audit_entries():
    conn = get_conn()
    rows = conn.execute("SELECT * FROM medicine_audit_log ORDER BY id DESC").fetchall()
    conn.close()
    return [parse_history_entry(dict(row)) for row in rows]


def export_records_payload():
    records = list_all_medicines()
    return {
        "exported_at": utc_now(),
        "record_count": len(records),
        "records": records,
        "audit_log": list_all_audit_entries(),
    }


def export_records_csv() -> str:
    records = list_all_medicines()
    buffer = StringIO()
    writer = DictWriter(buffer, fieldnames=["id", *MEDICINE_FIELDS])
    writer.writeheader()
    for record in records:
        writer.writerow({key: record.get(key) for key in ["id", *MEDICINE_FIELDS]})
    return buffer.getvalue()


def import_records(records: list[dict], skip_possible_duplicates: bool = True, note: str | None = None):
    imported = []
    skipped = []
    for record in records:
        candidate = {field: record.get(field) for field in MEDICINE_FIELDS if field in record}
        candidate["scanned_at"] = candidate.get("scanned_at") or utc_now()
        candidate["confidence"] = candidate.get("confidence") or "unknown"
        candidate["image_filename"] = candidate.get("image_filename") or "imported.jpg"
        candidate["image_path"] = candidate.get("image_path") or ""
        candidate["updated_at"] = candidate.get("updated_at") or utc_now()
        candidate["last_synced_at"] = candidate.get("last_synced_at")
        candidate["sync_status"] = "pending_sync" if USE_SUPABASE_SYNC else "local_only"

        duplicates = find_duplicate_candidates(candidate)
        if skip_possible_duplicates and duplicates.get("has_possible_duplicates"):
            skipped.append({"record": candidate, "duplicates": duplicates})
            continue

        saved = sqlite_insert(candidate)
        if note:
            conn = get_conn()
            conn.execute(
                """
                UPDATE medicine_audit_log
                SET note=?
                WHERE id=(
                    SELECT id FROM medicine_audit_log
                    WHERE medicine_id=? AND action='create'
                    ORDER BY id DESC
                    LIMIT 1
                )
                """,
                (note, saved["id"]),
            )
            conn.commit()
            conn.close()
        imported.append({"record": saved, "duplicates": duplicates})
    return {"imported": imported, "skipped": skipped}


def create_sqlite_backup() -> dict:
    BACKUPS_DIR.mkdir(parents=True, exist_ok=True)
    timestamp = utc_now().replace(":", "-")
    backup_path = BACKUPS_DIR / f"medicines-backup-{timestamp}.db"
    shutil.copy2(SQLITE_PATH, backup_path)
    return {
        "created_at": utc_now(),
        "path": str(backup_path),
        "size_bytes": Path(backup_path).stat().st_size,
    }


def create_bundle_backup() -> dict:
    BACKUPS_DIR.mkdir(parents=True, exist_ok=True)
    timestamp = utc_now().replace(":", "-")
    archive_path = BACKUPS_DIR / f"mediscan-bundle-{timestamp}.zip"

    with zipfile.ZipFile(archive_path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        if SQLITE_PATH.exists():
            archive.write(SQLITE_PATH, arcname="medicines.db")
        if SCANS_DIR.exists():
            for scan_file in SCANS_DIR.rglob("*"):
                if scan_file.is_file():
                    archive.write(scan_file, arcname=str(Path("scans") / scan_file.relative_to(SCANS_DIR)))

    return {
        "created_at": utc_now(),
        "path": str(archive_path),
        "size_bytes": archive_path.stat().st_size,
        "includes_scans": SCANS_DIR.exists(),
    }


def restore_bundle_backup(archive_base64: str, archive_filename: str = "mediscan-bundle.zip") -> dict:
    BACKUPS_DIR.mkdir(parents=True, exist_ok=True)
    safety_backup = create_bundle_backup()

    header_split = archive_base64.split(",", 1)
    raw_payload = header_split[1] if len(header_split) == 2 else header_split[0]
    archive_bytes = b64decode(raw_payload)

    with tempfile.TemporaryDirectory() as temp_dir_name:
        temp_dir = Path(temp_dir_name)
        uploaded_archive = temp_dir / archive_filename
        uploaded_archive.write_bytes(archive_bytes)

        with zipfile.ZipFile(uploaded_archive, "r") as archive:
            names = set(archive.namelist())
            if "medicines.db" not in names:
                raise ValueError("Bundle archive must include medicines.db")
            archive.extractall(temp_dir / "restore")

        restore_root = temp_dir / "restore"
        restored_db = restore_root / "medicines.db"
        restored_scans = restore_root / "scans"

        shutil.copy2(restored_db, SQLITE_PATH)
        if SCANS_DIR.exists():
            shutil.rmtree(SCANS_DIR)
        SCANS_DIR.mkdir(parents=True, exist_ok=True)
        if restored_scans.exists():
            for scan_path in restored_scans.rglob("*"):
                if scan_path.is_file():
                    destination = SCANS_DIR / scan_path.relative_to(restored_scans)
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(scan_path, destination)

    init_sqlite()
    return {
        "restored_at": utc_now(),
        "archive_filename": archive_filename,
        "safety_backup": safety_backup,
        "records": len(list_all_medicines()),
        "scan_files": len([path for path in SCANS_DIR.rglob("*") if path.is_file()]),
    }


def normalize_match_value(value):
    if value is None:
        return ""
    return re.sub(r"\s+", " ", str(value).strip().lower())


def score_duplicate_candidate(record: dict, candidate: dict) -> tuple[int, list[str]]:
    reasons = []
    score = 0

    record_barcode = normalize_match_value(record.get("barcode"))
    candidate_barcode = normalize_match_value(candidate.get("barcode"))
    if record_barcode and record_barcode == candidate_barcode:
        score += 100
        reasons.append("same barcode")

    record_brand = normalize_match_value(record.get("brand_name"))
    candidate_brand = normalize_match_value(candidate.get("brand_name"))
    record_generic = normalize_match_value(record.get("generic_name"))
    candidate_generic = normalize_match_value(candidate.get("generic_name"))
    record_strength = normalize_match_value(record.get("strength"))
    candidate_strength = normalize_match_value(candidate.get("strength"))
    record_batch = normalize_match_value(record.get("batch_number"))
    candidate_batch = normalize_match_value(candidate.get("batch_number"))
    record_expiry = normalize_match_value(record.get("expiry_date"))
    candidate_expiry = normalize_match_value(candidate.get("expiry_date"))
    record_manufacturer = normalize_match_value(record.get("manufacturer"))
    candidate_manufacturer = normalize_match_value(candidate.get("manufacturer"))

    if record_brand and record_batch and record_brand == candidate_brand and record_batch == candidate_batch:
        score += 95
        reasons.append("same brand and batch")

    if (
        record_generic
        and record_strength
        and record_generic == candidate_generic
        and record_strength == candidate_strength
        and record_manufacturer
        and record_manufacturer == candidate_manufacturer
    ):
        score += 85
        reasons.append("same generic, strength, and manufacturer")

    if record_brand and record_strength and record_brand == candidate_brand and record_strength == candidate_strength:
        score += 70
        reasons.append("same brand and strength")

    if record_batch and record_expiry and record_batch == candidate_batch and record_expiry == candidate_expiry:
        score += 65
        reasons.append("same batch and expiry")

    if record_generic and record_generic == candidate_generic:
        score += 20
        reasons.append("same generic")

    return score, reasons


def find_duplicate_candidates(record: dict, exclude_id=None, limit: int = 5) -> dict:
    conn = get_conn()
    rows = conn.execute("SELECT * FROM medicines ORDER BY id DESC LIMIT 200").fetchall()
    conn.close()

    matches = []
    for row in rows:
        candidate = dict(row)
        if exclude_id is not None and candidate.get("id") == exclude_id:
            continue
        score, reasons = score_duplicate_candidate(record, candidate)
        if score > 0:
            matches.append(
                {
                    "id": candidate.get("id"),
                    "brand_name": candidate.get("brand_name"),
                    "generic_name": candidate.get("generic_name"),
                    "strength": candidate.get("strength"),
                    "batch_number": candidate.get("batch_number"),
                    "expiry_date": candidate.get("expiry_date"),
                    "manufacturer": candidate.get("manufacturer"),
                    "scanned_at": candidate.get("scanned_at"),
                    "score": score,
                    "reasons": reasons,
                }
            )

    matches.sort(key=lambda item: (-item["score"], -(item["id"] or 0)))
    candidates = matches[:limit]
    return {
        "has_possible_duplicates": bool(candidates),
        "candidates": candidates,
    }
