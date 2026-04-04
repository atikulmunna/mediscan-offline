MEDICINE_COLUMNS = {
    "id": "INTEGER PRIMARY KEY AUTOINCREMENT",
    "scanned_at": "TEXT NOT NULL",
    "brand_name": "TEXT",
    "generic_name": "TEXT",
    "manufacturer": "TEXT",
    "batch_number": "TEXT",
    "serial_number": "TEXT",
    "dosage_form": "TEXT",
    "strength": "TEXT",
    "quantity": "TEXT",
    "manufacture_date": "TEXT",
    "expiry_date": "TEXT",
    "license_number": "TEXT",
    "barcode": "TEXT",
    "indications": "TEXT",
    "warnings": "TEXT",
    "storage_info": "TEXT",
    "active_ingredients": "TEXT",
    "raw_extracted": "TEXT",
    "confidence": "TEXT",
    "image_filename": "TEXT",
    "image_path": "TEXT",
    "sync_status": "TEXT NOT NULL DEFAULT 'local_only'",
    "last_synced_at": "TEXT",
    "updated_at": "TEXT NOT NULL",
}

SYNC_QUEUE_COLUMNS = {
    "id": "INTEGER PRIMARY KEY AUTOINCREMENT",
    "medicine_id": "INTEGER",
    "operation": "TEXT NOT NULL",
    "payload": "TEXT NOT NULL",
    "status": "TEXT NOT NULL DEFAULT 'pending'",
    "attempt_count": "INTEGER NOT NULL DEFAULT 0",
    "last_error": "TEXT",
    "next_attempt_at": "TEXT",
    "created_at": "TEXT NOT NULL",
    "updated_at": "TEXT NOT NULL",
}

MEDICINE_AUDIT_COLUMNS = {
    "id": "INTEGER PRIMARY KEY AUTOINCREMENT",
    "medicine_id": "INTEGER NOT NULL",
    "action": "TEXT NOT NULL",
    "changed_fields": "TEXT",
    "previous_snapshot": "TEXT",
    "next_snapshot": "TEXT",
    "note": "TEXT",
    "created_at": "TEXT NOT NULL",
}

MEDICINE_FIELDS = [column for column in MEDICINE_COLUMNS if column != "id"]
