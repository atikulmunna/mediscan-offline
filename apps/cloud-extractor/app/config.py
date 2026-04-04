import os
from pathlib import Path

from dotenv import load_dotenv

BASE_DIR = Path(__file__).resolve().parents[2]
load_dotenv(BASE_DIR / ".env")

PROVIDER_MODE = os.getenv("PROVIDER_MODE", "hybrid").strip().lower()
LLM_API_URL = os.getenv("LLM_API_URL", "https://api.openai.com/v1/chat/completions").strip()
LLM_API_KEY = os.getenv("LLM_API_KEY", "").strip()
LLM_MODEL = os.getenv("LLM_MODEL", "gpt-4.1-mini").strip()
LLM_TIMEOUT_SECONDS = max(10, int(os.getenv("LLM_TIMEOUT_SECONDS", "45")))
LLM_TEMPERATURE = float(os.getenv("LLM_TEMPERATURE", "0"))

LLM_ENABLED = bool(LLM_API_KEY and LLM_API_URL and LLM_MODEL)
