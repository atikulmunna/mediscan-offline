import base64
import importlib
import importlib.util
import io
import os

import numpy as np
from PIL import Image, ImageFilter, ImageOps

OCR_ENGINE = None
OCR_PROVIDER = None
OCR_IMPORT_ERROR = None

SUPPORTED_OCR_PROVIDERS = ("rapidocr", "paddleocr")


def _ocr_provider_preference() -> list[str]:
    configured = (os.getenv("OCR_PROVIDER") or "auto").strip().lower()
    if configured in SUPPORTED_OCR_PROVIDERS:
        return [configured]
    return ["rapidocr", "paddleocr"]


def _provider_available(provider: str) -> bool:
    module_name = "rapidocr_onnxruntime" if provider == "rapidocr" else "paddleocr"
    return importlib.util.find_spec(module_name) is not None


def ocr_available() -> bool:
    return any(_provider_available(provider) for provider in _ocr_provider_preference())


def _build_rapidocr_engine():
    rapidocr_module = importlib.import_module("rapidocr_onnxruntime")
    return rapidocr_module.RapidOCR()


def _build_paddleocr_engine():
    paddleocr_module = importlib.import_module("paddleocr")
    return paddleocr_module.PaddleOCR(
        device="cpu",
        enable_mkldnn=False,
        use_doc_orientation_classify=False,
        use_doc_unwarping=False,
        use_textline_orientation=False,
        lang="en",
    )


def get_ocr_engine() -> tuple[str, object]:
    global OCR_ENGINE, OCR_PROVIDER, OCR_IMPORT_ERROR
    if OCR_ENGINE is not None and OCR_PROVIDER is not None:
        return OCR_PROVIDER, OCR_ENGINE
    if OCR_IMPORT_ERROR is not None:
        raise OCR_IMPORT_ERROR

    errors = []
    for provider in _ocr_provider_preference():
        if not _provider_available(provider):
            continue
        try:
            engine = _build_rapidocr_engine() if provider == "rapidocr" else _build_paddleocr_engine()
            OCR_ENGINE = engine
            OCR_PROVIDER = provider
            OCR_IMPORT_ERROR = None
            return provider, engine
        except Exception as exc:
            errors.append(f"{provider}: {exc}")

    OCR_IMPORT_ERROR = RuntimeError(
        "No OCR engine could be initialized. " + ("; ".join(errors) if errors else "No supported OCR package is installed.")
    )
    raise OCR_IMPORT_ERROR


def preprocess_image_b64(image_b64: str) -> tuple[np.ndarray, dict]:
    image_bytes = base64.b64decode(image_b64.split(",", 1)[1] if "," in image_b64 else image_b64)
    with Image.open(io.BytesIO(image_bytes)) as original:
        original = ImageOps.exif_transpose(original)
        original_width, original_height = original.size

        working = original.convert("L")
        applied_steps = ["grayscale", "autocontrast"]
        working = ImageOps.autocontrast(working)

        if min(working.size) < 1200:
            scale = max(1.0, 1200 / min(working.size))
            resized = (int(working.width * scale), int(working.height * scale))
            working = working.resize(resized)
            applied_steps.append(f"upscale_{round(scale, 2)}x")

        working = working.filter(ImageFilter.SHARPEN)
        applied_steps.append("sharpen")

        processed_image = np.array(working.convert("RGB"))
        metadata = {
            "original_size": {"width": original_width, "height": original_height},
            "processed_size": {"width": working.width, "height": working.height},
            "steps": applied_steps,
        }
        return processed_image, metadata


def _run_rapidocr(ocr_engine, processed_image: np.ndarray, preprocessing: dict) -> dict:
    result, _elapsed = ocr_engine(processed_image)
    lines = []
    for entry in result or []:
        if len(entry) < 3:
            continue
        text = (entry[1] or "").strip()
        score = entry[2]
        if text:
            lines.append({"text": text, "score": score})
    return {"text": "\n".join(line["text"] for line in lines), "lines": lines, "preprocessing": preprocessing}


def _run_paddleocr(ocr_engine, processed_image: np.ndarray, preprocessing: dict) -> dict:
    result = ocr_engine.predict(input=processed_image)
    if not result:
        return {"text": "", "lines": [], "preprocessing": preprocessing}
    raw_rec_texts = result[0].get("rec_texts", []) if isinstance(result, list) else []
    raw_rec_scores = result[0].get("rec_scores", []) if isinstance(result, list) else []
    lines = []
    for idx, text in enumerate(raw_rec_texts):
        score = raw_rec_scores[idx] if idx < len(raw_rec_scores) else None
        if text:
            lines.append({"text": text.strip(), "score": score})
    return {"text": "\n".join(line["text"] for line in lines), "lines": lines, "preprocessing": preprocessing}


def run_ocr_on_image(image_b64: str) -> dict:
    provider, ocr_engine = get_ocr_engine()
    processed_image, preprocessing = preprocess_image_b64(image_b64)
    if provider == "rapidocr":
        result = _run_rapidocr(ocr_engine, processed_image, preprocessing)
    else:
        result = _run_paddleocr(ocr_engine, processed_image, preprocessing)
    return {**result, "provider": provider}


def combine_ocr_results(results: list[dict]) -> dict:
    combined_lines = []
    text_parts = []
    for index, result in enumerate(results, start=1):
        panel_name = result.get("panel_name") or f"Image {index}"
        panel_type = result.get("panel_type") or "other"
        panel_text = result.get("text", "").strip()
        if panel_text:
            text_parts.append(f"[{panel_name}]\n{panel_text}")
        for line in result.get("lines", []):
            combined_lines.append(
                {
                    **line,
                    "panel_name": panel_name,
                    "panel_type": panel_type,
                }
            )
    return {
        "text": "\n\n".join(text_parts),
        "lines": combined_lines,
        "images": results,
        "preprocessing": [
            {
                "panel_name": result.get("panel_name") or f"Image {index}",
                "panel_type": result.get("panel_type") or "other",
                "provider": result.get("provider"),
                **(result.get("preprocessing") or {}),
            }
            for index, result in enumerate(results, start=1)
        ],
        "provider": results[0].get("provider") if results else None,
    }
