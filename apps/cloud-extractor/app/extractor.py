import json
import re

import httpx

from app.config import LLM_API_KEY, LLM_API_URL, LLM_ENABLED, LLM_MODEL, LLM_TEMPERATURE, LLM_TIMEOUT_SECONDS, PROVIDER_MODE
from app.medicine_vocabulary import BRAND_ALIASES, BRAND_CANONICAL, correct_brand_name, correct_generic_line, normalize_token


KNOWN_DOSAGE_FORMS = [
    "tablet",
    "tablets",
    "capsule",
    "capsules",
    "syrup",
    "suspension",
    "ointment",
    "cream",
    "injection",
    "drop",
    "drops",
]

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
    "metronidazole",
    "flucloxacillin",
    "melatonin",
    "montelukast",
    "cranberry",
    "flunarizine",
]

MANUFACTURER_ALIASES = {
    "beximco",
    "square",
    "eskayef",
    "skf",
    "aristopharma",
    "renata",
    "incepta",
    "aci",
    "acme",
    "drug international",
    "radiant",
}

BRAND_ALIAS_NORMALIZED = {normalize_token(alias) for alias in BRAND_ALIASES}
BRAND_CANONICAL_NORMALIZED = {normalize_token(name) for name in BRAND_CANONICAL}

GENERIC_SUFFIXES = (
    "azole",
    "adine",
    "lukast",
    "nidazole",
    "floxacin",
    "cillin",
    "tidine",
    "mycin",
    "atonin",
)

TARGET_FIELDS = [
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
]


LLM_NORMALIZATION_PROMPT = """You are a pharmaceutical packaging normalization specialist.
You will receive OCR text captured from medicine packaging.

Your job:
- extract only what is actually supported by the OCR text
- do not invent, infer, or guess missing values
- prefer exact packaging wording over interpretation
- keep dates and codes exactly as written
- if the OCR is ambiguous, return null for that field

Return strict JSON only with this exact shape:
{
  "brand_name": string|null,
  "generic_name": string|null,
  "manufacturer": string|null,
  "batch_number": string|null,
  "serial_number": string|null,
  "dosage_form": string|null,
  "strength": string|null,
  "quantity": string|null,
  "manufacture_date": string|null,
  "expiry_date": string|null,
  "license_number": string|null,
  "barcode": string|null,
  "indications": string|null,
  "warnings": string|null,
  "storage_info": string|null,
  "active_ingredients": string|null,
  "confidence": "high"|"medium"|"low"
}

Rules:
- manufacturer should be the company line if present
- batch_number should prefer lot/batch text
- strength should include units
- quantity should include pack count or volume if present
- active_ingredients should preserve ingredient names and amounts
- confidence should reflect OCR quality and field certainty

Return JSON only. No markdown. No commentary."""


def clean_lines(ocr_text: str) -> list[str]:
    cleaned = []
    for raw_line in ocr_text.splitlines():
        line = raw_line.strip()
        if not line:
            continue
        if re.fullmatch(r"\[[^\]]+\]", line):
            continue
        cleaned.append(line)
    return cleaned


def match_first(patterns: list[str], text: str, flags: int = re.IGNORECASE):
    for pattern in patterns:
        match = re.search(pattern, text, flags)
        if match:
            return match.group(1).strip() if match.groups() else match.group(0).strip()
    return None


def evidence_line(lines: list[str], value: str | None) -> str | None:
    if not value:
        return None
    lowered = value.lower()
    for line in lines:
        if lowered in line.lower():
            return line
    return None


def detect_brand_name(lines: list[str]) -> str | None:
    for line in lines[:15]:
        lowered = line.strip().lower()
        compact = normalize_token(line)
        if lowered in BRAND_ALIASES or compact in BRAND_ALIAS_NORMALIZED:
            return correct_brand_name(line)
        if compact in BRAND_CANONICAL_NORMALIZED:
            return correct_brand_name(line)

    candidates = []
    frequencies = {}
    for line in lines[:15]:
        normalized = line.lower()
        frequencies[normalized] = frequencies.get(normalized, 0) + 1

    for line in lines[:15]:
        lowered = line.lower()
        letters = sum(1 for char in line if char.isalpha())
        digits = sum(1 for char in line if char.isdigit())
        if len(line) < 3 or letters < 2:
            continue
        if any(token in lowered for token in ["batch", "mfg", "lic", "warning", "store below", "ma no", "manufactured", "pharma"]):
            continue
        if lowered in MANUFACTURER_ALIASES:
            continue
        if any(keyword in lowered for keyword in GENERIC_KEYWORDS):
            continue
        if any(lowered.endswith(suffix) for suffix in GENERIC_SUFFIXES):
            continue

        score = frequencies.get(lowered, 1) * 3
        if re.fullmatch(r"[A-Za-z][A-Za-z0-9&+\- ]{1,24}", line):
            score += 2
        if len(line) <= 18:
            score += 1
        if digits <= letters:
            score += 1
        if any(char.isdigit() for char in line):
            score += 1
        if re.search(r"\b(?:mg|mcg|g|ml)\b", lowered):
            score -= 3
        if digits > letters:
            score -= 2
        candidates.append((score, line))

    if not candidates:
        return None
    candidates.sort(key=lambda item: (-item[0], len(item[1])))
    return candidates[0][1]


def detect_generic_name(lines: list[str]) -> str | None:
    candidates = []
    for line in lines:
        lowered = line.lower()
        if any(keyword in lowered for keyword in GENERIC_KEYWORDS):
            candidates.append(line)
        if "generic" in lowered or "composition" in lowered or "contains" in lowered:
            candidates.append(line)
        if re.search(r"\b\d+(?:\.\d+)?\s?(?:mg|mcg|g|ml)\b", lowered):
            alpha_tokens = re.findall(r"[a-z]{4,}", lowered)
            if alpha_tokens and not any(token in lowered for token in ["batch", "mfg", "lic", "ma no", "pharma"]):
                candidates.append(line)
        if any(lowered.endswith(suffix) or f" {suffix}" in lowered for suffix in GENERIC_SUFFIXES):
            candidates.append(line)
    if not candidates:
        return None
    candidates = sorted(
        set(candidates),
        key=lambda line: (
            -sum(1 for keyword in GENERIC_KEYWORDS if keyword in line.lower()),
            -bool(re.search(r"\b\d+(?:\.\d+)?\s?(?:mg|mcg|g|ml)\b", line.lower())),
            -len(line),
        ),
    )
    return candidates[0]


def detect_strength(text: str) -> str | None:
    return match_first(
        [
            r"\b(\d+(?:\.\d+)?\s?(?:mg|mcg|g|ml))\b",
            r"\b(\d+(?:\.\d+)?\s?(?:mg|mcg|g)\s*/\s*\d+(?:\.\d+)?\s?ml)\b",
        ],
        text,
    )


def detect_quantity(text: str) -> str | None:
    return match_first(
        [
            r"\b(\d+\s?(?:tablets?|capsules?|sachets?|ampoules?|vials?))\b",
            r"\b(\d+\s?ml)\b",
        ],
        text,
    )


def detect_batch(text: str) -> str | None:
    return match_first([r"(?:batch|b\.?no|lot)\s*[:#]?\s*([A-Z0-9\-\/]+)"], text)


def detect_serial(text: str) -> str | None:
    return match_first([r"(?:serial|sn)\s*[:#]?\s*([A-Z0-9\-\/]+)"], text)


def detect_mfg(text: str) -> str | None:
    return match_first(
        [
            r"(?:mfg|manufactured|manufacture date)\s*[:#]?\s*([A-Z0-9\-\/ ]+)",
            r"\b(mfg[: ]\s*\d{1,2}[\/\-]\d{2,4})\b",
        ],
        text,
    )


def detect_exp(text: str) -> str | None:
    return match_first(
        [
            r"(?:exp|expiry|expires|expiry date)\s*[:#]?\s*([A-Z0-9\-\/ ]+)",
            r"\b(exp[: ]\s*\d{1,2}[\/\-]\d{2,4})\b",
        ],
        text,
    )


def detect_license(text: str) -> str | None:
    return match_first([r"(?:license|licence|reg(?:istration)?\.?\s*no)\s*[:#]?\s*([A-Z0-9\-\/]+)"], text)


def detect_manufacturer(lines: list[str]) -> str | None:
    for line in lines:
        lowered = line.lower()
        if any(
            keyword in lowered
            for keyword in ["manufactured by", "marketed by", "pharma", "pharmaceutical", "laboratories", "healthcare", "limited", "ltd"]
        ):
            return line
    return None


def detect_dosage_form(text: str) -> str | None:
    lowered = text.lower()
    for form in KNOWN_DOSAGE_FORMS:
        if form in lowered:
            return form.title()
    return None


def detect_storage(lines: list[str]) -> str | None:
    for line in lines:
        if "store" in line.lower():
            return line
    return None


def detect_warnings(lines: list[str]) -> str | None:
    for line in lines:
        lowered = line.lower()
        if any(keyword in lowered for keyword in ["warning", "keep out of reach", "do not", "caution"]):
            return line
    return None


def detect_indications(lines: list[str]) -> str | None:
    for line in lines:
        lowered = line.lower()
        if any(keyword in lowered for keyword in ["indication", "used for", "for the treatment", "relief"]):
            return line
    return None


def detect_active_ingredients(lines: list[str]) -> str | None:
    for line in lines:
        lowered = line.lower()
        if any(keyword in lowered for keyword in ["composition", "contains", "ingredient", "active"]):
            return line
        if any(keyword in lowered for keyword in GENERIC_KEYWORDS) and re.search(r"\b\d+(?:\.\d+)?\s?(?:mg|mcg|g|ml)\b", lowered):
            return line
    return None


def infer_confidence(ocr_text: str, extracted: dict) -> str:
    filled = sum(1 for field, value in extracted.items() if field != "confidence" and value)
    line_count = len(clean_lines(ocr_text))
    if filled >= 8 and line_count >= 6:
        return "high"
    if filled >= 4:
        return "medium"
    return "low"


def field_confidence(field: str, value: str | None) -> str:
    if not value:
        return "low"
    if field in {"batch_number", "serial_number", "license_number"}:
        return "medium" if len(value) >= 4 else "low"
    if field in {"manufacture_date", "expiry_date"}:
        return "medium" if re.search(r"\d", value) else "low"
    if field in {"brand_name", "generic_name", "manufacturer"}:
        return "high" if len(value.split()) >= 1 else "low"
    return "high"


def build_field_meta(lines: list[str], extracted: dict) -> dict:
    meta = {}
    for field, value in extracted.items():
        if field == "confidence":
            continue
        confidence = field_confidence(field, value)
        needs_review = value is None or field in {"batch_number", "manufacture_date", "expiry_date", "manufacturer", "strength"}
        reason = None
        if value is None:
            reason = "missing"
        elif field in {"batch_number", "manufacture_date", "expiry_date"}:
            reason = "operationally sensitive field"
        elif field in {"manufacturer", "strength"}:
            reason = "commonly noisy in OCR packaging scans"
        meta[field] = {
            "value": value,
            "evidence": evidence_line(lines, value),
            "confidence": confidence,
            "needs_review": needs_review,
            "reason": reason,
        }
    return meta


def build_review_hints(extracted: dict, field_meta: dict) -> list[str]:
    hints = []
    for field in ["batch_number", "manufacture_date", "expiry_date", "manufacturer", "strength"]:
        meta = field_meta.get(field, {})
        if meta.get("needs_review"):
            label = field.replace("_", " ")
            if meta.get("value"):
                hints.append(f"Verify {label}: {meta['value']}")
            else:
                hints.append(f"Missing {label}: check the package manually")
    if extracted.get("confidence") == "low":
        hints.append("Low overall confidence: consider retaking the photo with better lighting and focus")
    return hints


def rule_normalize_ocr_text(ocr_text: str) -> tuple[dict, dict, list[str]]:
    lines = clean_lines(ocr_text)
    full_text = "\n".join(lines)
    extracted = {
        "brand_name": correct_brand_name(detect_brand_name(lines)),
        "generic_name": correct_generic_line(detect_generic_name(lines)),
        "manufacturer": detect_manufacturer(lines),
        "batch_number": detect_batch(full_text),
        "serial_number": detect_serial(full_text),
        "dosage_form": detect_dosage_form(full_text),
        "strength": detect_strength(full_text),
        "quantity": detect_quantity(full_text),
        "manufacture_date": detect_mfg(full_text),
        "expiry_date": detect_exp(full_text),
        "license_number": detect_license(full_text),
        "barcode": None,
        "indications": detect_indications(lines),
        "warnings": detect_warnings(lines),
        "storage_info": detect_storage(lines),
        "active_ingredients": detect_active_ingredients(lines),
    }
    extracted["confidence"] = infer_confidence(ocr_text, extracted)
    field_meta = build_field_meta(lines, extracted)
    review_hints = build_review_hints(extracted, field_meta)
    return extracted, field_meta, review_hints


def coerce_extracted_fields(data: dict) -> dict:
    normalized = {}
    for field in TARGET_FIELDS:
        value = data.get(field)
        if value is None:
            normalized[field] = None
        elif field == "confidence":
            confidence = str(value).strip().lower()
            normalized[field] = confidence if confidence in {"high", "medium", "low"} else "medium"
        else:
            normalized[field] = str(value).strip() or None
    if not normalized.get("confidence"):
        normalized["confidence"] = "medium"
    return normalized


async def llm_normalize_ocr_text(ocr_text: str) -> tuple[dict, dict, list[str], str]:
    payload = {
        "model": LLM_MODEL,
        "temperature": LLM_TEMPERATURE,
        "response_format": {"type": "json_object"},
        "messages": [
            {"role": "system", "content": LLM_NORMALIZATION_PROMPT},
            {"role": "user", "content": f"OCR text:\n{ocr_text}"},
        ],
    }
    headers = {
        "Authorization": f"Bearer {LLM_API_KEY}",
        "Content-Type": "application/json",
    }
    async with httpx.AsyncClient(timeout=LLM_TIMEOUT_SECONDS) as client:
        response = await client.post(LLM_API_URL, headers=headers, json=payload)
        response.raise_for_status()
        data = response.json()

    content = data["choices"][0]["message"]["content"]
    if isinstance(content, list):
        content = "".join(part.get("text", "") for part in content if isinstance(part, dict))
    match = re.search(r"\{.*\}", content, re.DOTALL)
    if not match:
        raise ValueError("Hosted LLM response did not contain JSON")
    extracted = coerce_extracted_fields(json.loads(match.group()))
    lines = clean_lines(ocr_text)
    field_meta = build_field_meta(lines, extracted)
    review_hints = build_review_hints(extracted, field_meta)
    return extracted, field_meta, review_hints, "cloud-llm"


async def normalize_ocr_text(ocr_text: str) -> tuple[dict, dict, list[str], str]:
    if PROVIDER_MODE in {"llm", "hybrid"} and LLM_ENABLED:
        try:
            return await llm_normalize_ocr_text(ocr_text)
        except Exception:
            if PROVIDER_MODE == "llm":
                raise
    extracted, field_meta, review_hints = rule_normalize_ocr_text(ocr_text)
    return extracted, field_meta, review_hints, "cloud-rules"
