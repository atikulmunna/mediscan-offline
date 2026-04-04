import sqlite3
from datetime import datetime, timezone

from app.core.config import BACKUPS_DIR, SCANS_DIR, SQLITE_PATH
from app.db.schema import MEDICINE_AUDIT_COLUMNS, MEDICINE_COLUMNS, SYNC_QUEUE_COLUMNS


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def get_conn():
    conn = sqlite3.connect(SQLITE_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def ensure_table_columns(conn: sqlite3.Connection, table_name: str, columns: dict[str, str]):
    existing = {
        row["name"]
        for row in conn.execute(f"PRAGMA table_info({table_name})").fetchall()
    }
    for name, definition in columns.items():
        if name not in existing:
            conn.execute(f"ALTER TABLE {table_name} ADD COLUMN {name} {definition}")


def init_sqlite():
    SCANS_DIR.mkdir(parents=True, exist_ok=True)
    BACKUPS_DIR.mkdir(parents=True, exist_ok=True)
    conn = get_conn()
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS medicines (
            id               INTEGER PRIMARY KEY AUTOINCREMENT,
            scanned_at       TEXT NOT NULL,
            brand_name       TEXT,
            generic_name     TEXT,
            manufacturer     TEXT,
            batch_number     TEXT,
            serial_number    TEXT,
            dosage_form      TEXT,
            strength         TEXT,
            quantity         TEXT,
            manufacture_date TEXT,
            expiry_date      TEXT,
            license_number   TEXT,
            barcode          TEXT,
            indications      TEXT,
            warnings         TEXT,
            storage_info     TEXT,
            active_ingredients TEXT,
            raw_extracted    TEXT,
            confidence       TEXT
        )
        """
    )
    ensure_table_columns(conn, "medicines", MEDICINE_COLUMNS)
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS sync_queue (
            id            INTEGER PRIMARY KEY AUTOINCREMENT,
            medicine_id   INTEGER,
            operation     TEXT NOT NULL,
            payload       TEXT NOT NULL,
            status        TEXT NOT NULL DEFAULT 'pending',
            attempt_count INTEGER NOT NULL DEFAULT 0,
            last_error    TEXT,
            next_attempt_at TEXT,
            created_at    TEXT NOT NULL,
            updated_at    TEXT NOT NULL
        )
        """
    )
    ensure_table_columns(conn, "sync_queue", SYNC_QUEUE_COLUMNS)
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS medicine_audit_log (
            id                INTEGER PRIMARY KEY AUTOINCREMENT,
            medicine_id       INTEGER NOT NULL,
            action            TEXT NOT NULL,
            changed_fields    TEXT,
            previous_snapshot TEXT,
            next_snapshot     TEXT,
            note              TEXT,
            created_at        TEXT NOT NULL
        )
        """
    )
    ensure_table_columns(conn, "medicine_audit_log", MEDICINE_AUDIT_COLUMNS)
    conn.commit()
    conn.close()
