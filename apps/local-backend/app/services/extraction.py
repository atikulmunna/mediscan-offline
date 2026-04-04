import base64
import json
import re
import uuid
from datetime import datetime

import httpx

from app.core.config import (
    APP_MODE,
    CLOUD_EXTRACT_API_KEY,
    CLOUD_EXTRACT_URL,
    CLOUD_EXTRACTION_ENABLED,
    LOCAL_EXTRACTION_ENABLED,
    OLLAMA_MODEL,
    OLLAMA_URL,
    SCANS_DIR,
)
from app.db.database import utc_now

EXTRACTION_PROMPT = """You are a pharmaceutical data extraction specialist.
You are given OCR text extracted from a medicine package image. Normalize it into structured fields.

Rules:
- Do not guess values that are not present in the OCR text.
- Prefer exact text from the package over interpretation.
- If there are multiple candidates, choose the most explicit one.
- Return null for anything missing or uncertain.
- Keep dates exactly as shown in the OCR text.

Return ONLY a valid JSON object with these exact keys:
{
  "brand_name": "commercial product name",
  "generic_name": "INN / generic drug name",
  "manufacturer": "company name and address",
  "batch_number": "batch/lot number",
  "serial_number": "serial number if present",
  "dosage_form": "tablet/capsule/syrup/injection/etc",
  "strength": "e.g. 500mg, 10mg/5ml",
  "quantity": "e.g. 30 tablets, 100ml",
  "manufacture_date": "manufacturing date exactly as shown",
  "expiry_date": "expiry/best before date exactly as shown",
  "license_number": "drug license or registration number",
  "barcode": "barcode or NDC number if readable",
  "indications": "what the medicine is used for if stated",
  "warnings": "warnings, contraindications, side effects if stated",
  "storage_info": "storage conditions e.g. store below 25C",
  "active_ingredients": "list of active ingredients with amounts",
  "confidence": "high/medium/low based on image clarity and legibility"
}

Return ONLY the JSON. No explanation, no markdown, no extra text."""

GENERIC_KEYWORDS = [
    "paracetamol",
    "amoxicillin",
    "omeprazole",
    "esomeprazole",
    "metformin",
    "ibuprofen",
    "azithromycin",
    "cetirizine",
    "pantoprazole",
    "diclofenac",
    "clopidogrel",
    "atorvastatin",
    "rupatadine",
]

PANEL_TYPE_LABELS = {
    "packet_date_side": "Packet Date Side",
    "packet_detail_side": "Packet Detail Side",
    "strip": "Strip",
    "other": "Other",
}

FIELD_PANEL_PRIORITY = {
    "batch_number": ["packet_date_side", "packet_detail_side", "strip", "other"],
    "manufacture_date": ["packet_date_side", "packet_detail_side", "strip", "other"],
    "expiry_date": ["packet_date_side", "packet_detail_side", "strip", "other"],
    "license_number": ["packet_date_side", "packet_detail_side", "strip", "other"],
    "quantity": ["packet_detail_side", "packet_date_side", "strip", "other"],
    "brand_name": ["strip", "packet_detail_side", "packet_date_side", "other"],
    "generic_name": ["strip", "packet_detail_side", "packet_date_side", "other"],
    "strength": ["strip", "packet_detail_side", "packet_date_side", "other"],
    "manufacturer": ["packet_detail_side", "packet_date_side", "strip", "other"],
    "active_ingredients": ["strip", "packet_detail_side", "packet_date_side", "other"],
}

PANEL_FORCE_OVERRIDE_FIELDS = {"batch_number", "manufacture_date", "expiry_date", "license_number", "quantity"}


def _normalize_panel_type(value: str | None) -> str:
    normalized = (value or "other").strip().lower().replace(" ", "_")
    return normalized if normalized in PANEL_TYPE_LABELS else "other"


def save_image_locally(image_b64: str, filename=None) -> tuple[str, str]:
    if "," in image_b64:
        header, image_b64 = image_b64.split(",", 1)
    else:
        header = ""
    extension = "jpg"
    mime_match = re.search(r"data:image/([a-zA-Z0-9+]+);base64", header)
    if filename and "." in filename:
        extension = filename.rsplit(".", 1)[-1].lower()
    elif mime_match:
        extension = mime_match.group(1).replace("jpeg", "jpg")
    safe_extension = re.sub(r"[^a-z0-9]", "", extension) or "jpg"
    generated_name = f"{datetime.now().strftime('%Y%m%d-%H%M%S')}-{uuid.uuid4().hex[:8]}.{safe_extension}"
    image_path = SCANS_DIR / generated_name
    image_path.write_bytes(base64.b64decode(image_b64))
    return generated_name, str(image_path)


def save_images_locally(images: list[dict]) -> list[dict]:
    saved_images = []
    for index, image in enumerate(images, start=1):
        filename, path = save_image_locally(image["image_base64"], image.get("image_filename"))
        panel_type = _normalize_panel_type(image.get("panel_type"))
        saved_images.append(
            {
                "panel_name": image.get("panel_name") or f"Image {index}",
                "panel_type": panel_type,
                "panel_type_label": PANEL_TYPE_LABELS[panel_type],
                "image_filename": filename,
                "image_path": path,
            }
        )
    return saved_images


async def extract_with_llava(ocr_text: str) -> dict:
    payload = {
        "model": OLLAMA_MODEL,
        "prompt": f"{EXTRACTION_PROMPT}\n\nOCR_TEXT:\n{ocr_text}",
        "stream": False,
        "options": {"temperature": 0.1, "num_predict": 768},
    }
    async with httpx.AsyncClient(timeout=120) as client:
        response = await client.post(OLLAMA_URL, json=payload)
        response.raise_for_status()
        raw_text = response.json().get("response", "")
    match = re.search(r"\{.*\}", raw_text, re.DOTALL)
    if match:
        return json.loads(match.group())
    raise ValueError(f"Could not parse JSON from LLaVA: {raw_text[:300]}")


def build_local_field_meta(extracted: dict) -> dict:
    review_sensitive = {"batch_number", "manufacture_date", "expiry_date", "manufacturer", "strength"}
    meta = {}
    for field, value in extracted.items():
        if field == "confidence":
            continue
        meta[field] = {
            "value": value,
            "evidence": None,
            "confidence": extracted.get("confidence", "unknown"),
            "needs_review": field in review_sensitive or not value,
            "reason": "local fallback extraction" if field in review_sensitive else ("missing" if not value else None),
        }
    return meta


def build_review_hints(extracted: dict, field_meta: dict) -> list[str]:
    hints = []
    for field in ["batch_number", "manufacture_date", "expiry_date", "manufacturer", "strength"]:
        meta = field_meta.get(field, {})
        label = field.replace("_", " ")
        source_hint = ""
        if meta.get("source_panel_name"):
            source_hint = f" from {meta['source_panel_name']}"
        if meta.get("needs_review") and meta.get("value"):
            hints.append(f"Verify {label}{source_hint}: {meta['value']}")
        elif meta.get("needs_review"):
            hints.append(f"Missing {label}: verify manually")
    if extracted.get("confidence") == "low":
        hints.append("Low overall confidence: retake the photo or compare against the package manually")
    return hints


async def extract_with_cloud(ocr_text: str) -> dict:
    if not CLOUD_EXTRACTION_ENABLED:
        raise RuntimeError("Cloud extraction is not configured")
    headers = {"Content-Type": "application/json"}
    if CLOUD_EXTRACT_API_KEY:
        headers["Authorization"] = f"Bearer {CLOUD_EXTRACT_API_KEY}"
    payload = {
        "ocr_text": ocr_text,
        "mode": APP_MODE,
        "fields": [
            "brand_name",
            "generic_name",
            "manufacturer",
            "batch_number",
            "serial_number",
            "dosage_form",
            "strength",
            "quantity",
            "manufacture_date",
            "expiry_date",
            "license_number",
            "barcode",
            "indications",
            "warnings",
            "storage_info",
            "active_ingredients",
            "confidence",
        ],
    }
    async with httpx.AsyncClient(timeout=45) as client:
        response = await client.post(CLOUD_EXTRACT_URL, headers=headers, json=payload)
        response.raise_for_status()
        data = response.json()
    if "extracted" in data and isinstance(data["extracted"], dict):
        return data
    raise ValueError("Cloud extraction response was not a JSON object")


async def ping_cloud_extractor() -> bool:
    if not CLOUD_EXTRACTION_ENABLED:
        return False
    headers = {}
    if CLOUD_EXTRACT_API_KEY:
        headers["Authorization"] = f"Bearer {CLOUD_EXTRACT_API_KEY}"
    health_url = CLOUD_EXTRACT_URL[:-8] + "/health" if CLOUD_EXTRACT_URL.endswith("/extract") else f"{CLOUD_EXTRACT_URL}/health"
    async with httpx.AsyncClient(timeout=10) as client:
        response = await client.get(health_url, headers=headers)
        return response.status_code < 300


def extraction_mode_summary() -> dict:
    return {
        "app_mode": APP_MODE,
        "cloud_enabled": CLOUD_EXTRACTION_ENABLED,
        "local_enabled": LOCAL_EXTRACTION_ENABLED,
        "strategy": (
            "cloud_primary_local_fallback"
            if APP_MODE == "hybrid"
            else "cloud_only"
            if APP_MODE == "cloud"
            else "local_only"
        ),
    }


async def extract_structured_fields(ocr_text: str) -> dict:
    errors = []
    if APP_MODE in {"hybrid", "cloud"} and CLOUD_EXTRACTION_ENABLED:
        try:
            cloud_result = await extract_with_cloud(ocr_text)
            return {
                "extracted": cloud_result.get("extracted", {}),
                "provider": cloud_result.get("provider", "cloud"),
                "field_meta": cloud_result.get("field_meta", {}),
                "review_hints": cloud_result.get("review_hints", []),
            }
        except Exception as exc:
            errors.append(f"cloud:{exc}")
            if APP_MODE == "cloud":
                raise
    if APP_MODE in {"hybrid", "local"} and LOCAL_EXTRACTION_ENABLED:
        try:
            extracted = await extract_with_llava(ocr_text)
            field_meta = build_local_field_meta(extracted)
            return {
                "extracted": extracted,
                "provider": "local",
                "field_meta": field_meta,
                "review_hints": build_review_hints(extracted, field_meta),
            }
        except Exception as exc:
            errors.append(f"local:{exc}")
            raise RuntimeError("; ".join(errors)) from exc
    raise RuntimeError("; ".join(errors) if errors else "No extraction provider is enabled")


def _clean_lines(text: str) -> list[str]:
    return [line.strip() for line in text.splitlines() if line.strip()]


def _match_first(patterns: list[str], text: str):
    for pattern in patterns:
        match = re.search(pattern, text, re.IGNORECASE)
        if match:
            return match.group(1).strip() if match.groups() else match.group(0).strip()
    return None


def _detect_brand_name(lines: list[str]) -> str | None:
    candidates = []
    frequencies = {}
    for line in lines[:12]:
        normalized = line.lower()
        frequencies[normalized] = frequencies.get(normalized, 0) + 1

    for line in lines[:12]:
        lowered = line.lower()
        letters = sum(1 for char in line if char.isalpha())
        digits = sum(1 for char in line if char.isdigit())
        if len(line) < 3 or letters < 2:
            continue
        if any(token in lowered for token in ["batch", "mfg", "lic", "ma no", "manufactured", "expiry", "exp", "pharma"]):
            continue
        if any(keyword in lowered for keyword in GENERIC_KEYWORDS):
            continue

        score = frequencies.get(lowered, 1) * 3
        if len(line) <= 18:
            score += 1
        if digits <= letters:
            score += 1
        candidates.append((score, line))

    if not candidates:
        return None
    candidates.sort(key=lambda item: (-item[0], len(item[1])))
    return candidates[0][1]


def _detect_generic_name(lines: list[str], text: str) -> str | None:
    for line in lines:
        lowered = line.lower()
        if any(keyword in lowered for keyword in GENERIC_KEYWORDS):
            return line
        if any(keyword in lowered for keyword in ["generic", "composition", "contains", "ingredient"]):
            return line
    match = _match_first([r"([A-Za-z][A-Za-z ,&()-]+(?:mg|mcg|g|ml))"], text)
    return match


def _detect_strength(text: str) -> str | None:
    return _match_first(
        [
            r"\b(\d+(?:\.\d+)?\s?(?:mg|mcg|g|ml))\b",
            r"\b(\d+(?:\.\d+)?\s?(?:mg|mcg|g)\s*/\s*\d+(?:\.\d+)?\s?ml)\b",
        ],
        text,
    )


def _detect_quantity(text: str) -> str | None:
    return _match_first(
        [
            r"\b(\d+\s?(?:tablets?|capsules?|strips?|ampoules?|vials?|pieces?|pcs))\b",
            r"\b(\d+\s?x\s?\d+\s?(?:tablets?|capsules?))\b",
            r"\b(\d+\s?ml)\b",
        ],
        text,
    )


def _detect_batch(text: str) -> str | None:
    return _match_first([r"(?:batch|b\.?no|lot)\s*[:#]?\s*([A-Z0-9\-\/]+)"], text)


def _detect_manufacture_date(text: str) -> str | None:
    return _match_first(
        [
            r"(?:mfg|manufactured|manufacture date)\s*[:#]?\s*([A-Z0-9\-\/ ]+)",
            r"\b(mfg[: ]\s*\d{1,2}[\/\-]\d{2,4})\b",
        ],
        text,
    )


def _detect_expiry_date(text: str) -> str | None:
    return _match_first(
        [
            r"(?:exp|expiry|expires|expiry date)\s*[:#]?\s*([A-Z0-9\-\/ ]+)",
            r"\b(exp[: ]\s*\d{1,2}[\/\-]\d{2,4})\b",
        ],
        text,
    )


def _detect_license_number(text: str) -> str | None:
    return _match_first(
        [
            r"(?:license|licence|mfg\.?\s*lic\.?\s*no\.?|reg(?:istration)?\.?\s*no)\s*[:#]?\s*([A-Z0-9&\-\/]+)",
            r"(?:ma\s*no\.?)\s*[:#]?\s*([A-Z0-9\-\/]+)",
        ],
        text,
    )


def _detect_manufacturer(lines: list[str]) -> str | None:
    for line in lines:
        lowered = line.lower()
        if any(
            keyword in lowered
            for keyword in ["manufactured by", "marketed by", "pharma", "pharmaceutical", "laboratories", "healthcare", "limited", "ltd"]
        ):
            return line
    return None


def _detect_active_ingredients(lines: list[str]) -> str | None:
    for line in lines:
        lowered = line.lower()
        if any(keyword in lowered for keyword in ["composition", "contains", "ingredient", "active"]):
            return line
        if any(keyword in lowered for keyword in GENERIC_KEYWORDS) and re.search(r"\b\d+(?:\.\d+)?\s?(?:mg|mcg|g|ml)\b", lowered):
            return line
    return None


def _panel_candidates(panel: dict) -> dict:
    text = panel.get("text", "")
    lines = _clean_lines(text)
    return {
        "brand_name": _detect_brand_name(lines),
        "generic_name": _detect_generic_name(lines, text),
        "manufacturer": _detect_manufacturer(lines),
        "batch_number": _detect_batch(text),
        "strength": _detect_strength(text),
        "quantity": _detect_quantity(text),
        "manufacture_date": _detect_manufacture_date(text),
        "expiry_date": _detect_expiry_date(text),
        "license_number": _detect_license_number(text),
        "active_ingredients": _detect_active_ingredients(lines),
    }


def _find_evidence_line(panel: dict, value: str | None) -> str | None:
    if not value:
        return None
    lowered = value.lower()
    for line in panel.get("lines", []):
        text = (line.get("text") or "").strip()
        if text and lowered in text.lower():
            return text
    for line in _clean_lines(panel.get("text", "")):
        if lowered in line.lower():
            return line
    return None


def _panel_rank(field: str, panel_type: str) -> int:
    priority = FIELD_PANEL_PRIORITY.get(field, ["other"])
    try:
        return priority.index(panel_type)
    except ValueError:
        return len(priority)


def apply_panel_aware_overrides(extraction_result: dict, ocr: dict) -> dict:
    extracted = {**(extraction_result.get("extracted") or {})}
    field_meta = {**(extraction_result.get("field_meta") or {})}
    panel_sources = {}
    panels = ocr.get("images") or []

    for panel in panels:
        panel["panel_type"] = _normalize_panel_type(panel.get("panel_type"))

    for field, priority in FIELD_PANEL_PRIORITY.items():
        best = None
        for panel in panels:
            panel_type = panel.get("panel_type") or "other"
            if panel_type not in priority:
                continue
            value = _panel_candidates(panel).get(field)
            if not value:
                continue
            candidate = {
                "value": value,
                "panel_name": panel.get("panel_name"),
                "panel_type": panel_type,
                "panel_type_label": PANEL_TYPE_LABELS.get(panel_type, panel_type.replace("_", " ").title()),
                "evidence": _find_evidence_line(panel, value),
                "rank": _panel_rank(field, panel_type),
            }
            if best is None or candidate["rank"] < best["rank"]:
                best = candidate

        if not best:
            continue

        current_value = extracted.get(field)
        current_meta = field_meta.get(field, {})
        current_rank = _panel_rank(field, _normalize_panel_type(current_meta.get("source_panel_type")))
        should_override = (
            not current_value
            or (
                field in PANEL_FORCE_OVERRIDE_FIELDS
                and (
                    current_rank > best["rank"]
                    or best["rank"] <= 1
                )
            )
        )
        if should_override:
            extracted[field] = best["value"]

        if extracted.get(field) == best["value"]:
            field_meta[field] = {
                **current_meta,
                "value": best["value"],
                "evidence": best["evidence"] or current_meta.get("evidence"),
                "source_panel_name": best["panel_name"],
                "source_panel_type": best["panel_type"],
                "source_panel_type_label": best["panel_type_label"],
                "panel_aware": True,
            }
            panel_sources[field] = {
                "panel_name": best["panel_name"],
                "panel_type": best["panel_type"],
                "panel_type_label": best["panel_type_label"],
                "value": best["value"],
            }

    review_hints = list(extraction_result.get("review_hints") or [])
    if any(panel.get("panel_type") == "packet_date_side" for panel in panels):
        review_hints.append("Packet date side captured: batch, manufacture date, expiry, and license values should come from that side first")
    if any(panel.get("panel_type") == "strip" for panel in panels):
        review_hints.append("Strip captured: brand, strength, and generic name can be cross-checked against the strip text")

    deduped_hints = []
    seen = set()
    for hint in review_hints:
        if hint not in seen:
            deduped_hints.append(hint)
            seen.add(hint)

    return {
        **extraction_result,
        "extracted": extracted,
        "field_meta": field_meta,
        "review_hints": deduped_hints,
        "panel_sources": panel_sources,
    }


def build_draft(extraction_result: dict, ocr: dict, saved_images: list[dict]) -> dict:
    extracted = extraction_result.get("extracted", {})
    primary_image = saved_images[0] if saved_images else {"image_filename": None, "image_path": None}
    return {
        "scanned_at": utc_now(),
        "brand_name": extracted.get("brand_name"),
        "generic_name": extracted.get("generic_name"),
        "manufacturer": extracted.get("manufacturer"),
        "batch_number": extracted.get("batch_number"),
        "serial_number": extracted.get("serial_number"),
        "dosage_form": extracted.get("dosage_form"),
        "strength": extracted.get("strength"),
        "quantity": extracted.get("quantity"),
        "manufacture_date": extracted.get("manufacture_date"),
        "expiry_date": extracted.get("expiry_date"),
        "license_number": extracted.get("license_number"),
        "barcode": extracted.get("barcode"),
        "indications": extracted.get("indications"),
        "warnings": extracted.get("warnings"),
        "storage_info": extracted.get("storage_info"),
        "active_ingredients": extracted.get("active_ingredients"),
        "raw_extracted": json.dumps(
            {
                "ocr": ocr,
                "normalized": extracted,
                "provider": extraction_result.get("provider"),
                "field_meta": extraction_result.get("field_meta", {}),
                "panel_sources": extraction_result.get("panel_sources", {}),
                "review_hints": extraction_result.get("review_hints", []),
                "images": saved_images,
            }
        ),
        "confidence": extracted.get("confidence", "unknown"),
        "image_filename": primary_image.get("image_filename"),
        "image_path": primary_image.get("image_path"),
    }
