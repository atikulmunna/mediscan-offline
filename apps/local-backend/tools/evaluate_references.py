import argparse
import asyncio
import base64
import json
import sys
from pathlib import Path

CURRENT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = CURRENT_DIR.parent
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from app.db.database import utc_now
from app.services.evaluation import build_markdown_report, discover_reference_images, summarize_evaluation
from app.services.extraction import extract_structured_fields
from app.services.ocr import combine_ocr_results, run_ocr_on_image


REPO_ROOT = Path(__file__).resolve().parents[3]
DEFAULT_REFERENCE_DIR = REPO_ROOT / "references" / "raw"
DEFAULT_NOTES_DIR = REPO_ROOT / "references" / "notes"


def image_to_data_url(path: Path) -> str:
    suffix = path.suffix.lower().lstrip(".")
    mime = "jpeg" if suffix in {"jpg", "jpeg"} else suffix
    encoded = base64.b64encode(path.read_bytes()).decode("ascii")
    return f"data:image/{mime};base64,{encoded}"


async def evaluate_image(path: Path) -> dict:
    result = {"filename": path.name, "path": str(path), "status": "ok"}
    try:
        ocr_result = run_ocr_on_image(image_to_data_url(path))
        ocr_result["panel_name"] = path.stem
        combined = combine_ocr_results([ocr_result])
        result["ocr_text"] = combined.get("text", "")
        result["ocr_lines"] = len(combined.get("lines", []))
        extraction = await extract_structured_fields(combined.get("text", ""))
        result["provider"] = extraction.get("provider")
        result["extracted"] = extraction.get("extracted", {})
        result["field_meta"] = extraction.get("field_meta", {})
        result["review_hints"] = extraction.get("review_hints", [])
        result["confidence"] = result["extracted"].get("confidence")
    except Exception as exc:
        result["status"] = "error"
        result["error"] = str(exc)
    return result


async def main():
    parser = argparse.ArgumentParser(description="Evaluate reference medicine images through OCR and extraction.")
    parser.add_argument("--reference-dir", default=str(DEFAULT_REFERENCE_DIR))
    parser.add_argument("--notes-dir", default=str(DEFAULT_NOTES_DIR))
    args = parser.parse_args()

    reference_dir = Path(args.reference_dir)
    notes_dir = Path(args.notes_dir)
    notes_dir.mkdir(parents=True, exist_ok=True)

    images = discover_reference_images(reference_dir)
    if not images:
        raise SystemExit(f"No reference images found in {reference_dir}")

    results = []
    for image_path in images:
        results.append(await evaluate_image(image_path))

    report = {
        "generated_at": utc_now(),
        "reference_dir": str(reference_dir),
        "summary": summarize_evaluation(results),
        "results": results,
    }

    json_path = notes_dir / "eval-report.json"
    md_path = notes_dir / "eval-report.md"
    json_path.write_text(json.dumps(report, indent=2, ensure_ascii=True), encoding="utf-8")
    md_path.write_text(build_markdown_report(report), encoding="utf-8")

    print(f"Wrote {json_path}")
    print(f"Wrote {md_path}")


if __name__ == "__main__":
    asyncio.run(main())
