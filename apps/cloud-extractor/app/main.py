from fastapi import FastAPI, HTTPException

from app.config import LLM_ENABLED, LLM_MODEL, PROVIDER_MODE
from app.extractor import normalize_ocr_text
from app.models import ExtractRequest, ExtractResponse

app = FastAPI(title="Medicine Cloud Extractor")


@app.get("/")
async def root():
    return {
        "status": "ok",
        "provider": "cloud-llm" if LLM_ENABLED and PROVIDER_MODE in {"llm", "hybrid"} else "cloud-rules",
        "provider_mode": PROVIDER_MODE,
        "llm_enabled": LLM_ENABLED,
        "llm_model": LLM_MODEL if LLM_ENABLED else None,
    }


@app.get("/health")
async def health():
    return {
        "status": "ok",
        "provider": "cloud-llm" if LLM_ENABLED and PROVIDER_MODE in {"llm", "hybrid"} else "cloud-rules",
        "provider_mode": PROVIDER_MODE,
        "llm_enabled": LLM_ENABLED,
        "llm_model": LLM_MODEL if LLM_ENABLED else None,
    }


@app.post("/extract", response_model=ExtractResponse)
async def extract_fields(request: ExtractRequest):
    if not request.ocr_text.strip():
        raise HTTPException(422, "ocr_text is required")
    extracted, field_meta, review_hints, provider = await normalize_ocr_text(request.ocr_text)
    return ExtractResponse(extracted=extracted, provider=provider, field_meta=field_meta, review_hints=review_hints)
