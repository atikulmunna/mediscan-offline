import os
from pathlib import Path

from dotenv import load_dotenv

BASE_DIR = Path(__file__).resolve().parents[2]
load_dotenv(BASE_DIR / ".env")

SQLITE_PATH = BASE_DIR / "medicines.db"
SCANS_DIR = BASE_DIR / "scans"
BACKUPS_DIR = BASE_DIR / "backups"

APP_MODE = os.getenv("APP_MODE", "hybrid").strip().lower()

OLLAMA_URL = "http://localhost:11434/api/generate"
OLLAMA_MODEL = os.getenv("OLLAMA_MODEL", "llava")
LOCAL_EXTRACTION_ENABLED = os.getenv("LOCAL_EXTRACTION_ENABLED", "true").strip().lower() != "false"

CLOUD_EXTRACT_URL = os.getenv("CLOUD_EXTRACT_URL", "").rstrip("/")
CLOUD_EXTRACT_API_KEY = os.getenv("CLOUD_EXTRACT_API_KEY", "")
CLOUD_EXTRACTION_ENABLED = bool(CLOUD_EXTRACT_URL)

SUPABASE_URL = os.getenv("SUPABASE_URL", "").rstrip("/")
SUPABASE_KEY = os.getenv("SUPABASE_KEY", "")
SUPABASE_TABLE = os.getenv("SUPABASE_TABLE", "medicines")
USE_SUPABASE_SYNC = bool(SUPABASE_URL and SUPABASE_KEY)

SYNC_MAX_ATTEMPTS = max(1, int(os.getenv("SYNC_MAX_ATTEMPTS", "5")))
SYNC_RETRY_DELAY_SECONDS = max(5, int(os.getenv("SYNC_RETRY_DELAY_SECONDS", "300")))
SYNC_BACKGROUND_INTERVAL_SECONDS = max(0, int(os.getenv("SYNC_BACKGROUND_INTERVAL_SECONDS", "120")))
SYNC_BACKGROUND_ENABLED = USE_SUPABASE_SYNC and SYNC_BACKGROUND_INTERVAL_SECONDS > 0
