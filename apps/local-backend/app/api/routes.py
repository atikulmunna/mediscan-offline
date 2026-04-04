import asyncio
import httpx
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import PlainTextResponse

from app.core.config import (
    CLOUD_EXTRACTION_ENABLED,
    LOCAL_EXTRACTION_ENABLED,
    OLLAMA_MODEL,
    SYNC_BACKGROUND_ENABLED,
    SYNC_BACKGROUND_INTERVAL_SECONDS,
    USE_SUPABASE_SYNC,
)
from app.db.database import init_sqlite, utc_now
from app.models import ImportPayload, MedicineCreate, MedicineUpdate, RestoreBundlePayload, ScanRequest
from app.services.extraction import (
    apply_panel_aware_overrides,
    build_draft,
    extract_structured_fields,
    extraction_mode_summary,
    ping_cloud_extractor,
    save_images_locally,
)
from app.services.ocr import combine_ocr_results, ocr_available, run_ocr_on_image
from app.services.repository import (
    create_bundle_backup,
    create_sqlite_backup,
    export_records_csv,
    export_records_payload,
    find_duplicate_candidates,
    get_medicine_history,
    get_sync_stats,
    list_sync_queue_items,
    import_records,
    medicine_to_supabase_payload,
    queue_upsert_for_record,
    queue_sync,
    restore_bundle_backup,
    sqlite_delete,
    sqlite_get,
    sqlite_insert,
    sqlite_select,
    sqlite_update,
)
from app.services.supabase import supa_ping
from app.services.sync import run_sync
from app.db.database import get_conn

app = FastAPI(title="Medicine Scanner API")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

init_sqlite()


async def _sync_background_worker():
    while True:
        try:
            await run_sync(limit=10, trigger="background")
        except Exception:
            pass
        await asyncio.sleep(SYNC_BACKGROUND_INTERVAL_SECONDS)


@app.on_event("startup")
async def startup_sync_worker():
    if SYNC_BACKGROUND_ENABLED:
        app.state.sync_task = asyncio.create_task(_sync_background_worker())


@app.on_event("shutdown")
async def shutdown_sync_worker():
    task = getattr(app.state, "sync_task", None)
    if task:
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass


@app.get("/health")
async def health():
    try:
        async with httpx.AsyncClient(timeout=5) as client:
            response = await client.get("http://localhost:11434/api/tags")
            models = [model["name"] for model in response.json().get("models", [])]
        ollama_ok = True
    except Exception:
        models = []
        ollama_ok = False

    supabase_ok = False
    if USE_SUPABASE_SYNC:
        try:
            supabase_ok = await supa_ping()
        except Exception:
            supabase_ok = False

    cloud_extraction_ok = False
    if CLOUD_EXTRACTION_ENABLED:
        try:
            cloud_extraction_ok = await ping_cloud_extractor()
        except Exception:
            cloud_extraction_ok = False

    return {
        "status": "ok",
        "storage_mode": "local_first",
        "ocr": ocr_available(),
        "ollama": ollama_ok,
        "available_models": models,
        "target_model": OLLAMA_MODEL,
        "supabase_configured": USE_SUPABASE_SYNC,
        "supabase_connected": supabase_ok,
        "cloud_extraction_configured": CLOUD_EXTRACTION_ENABLED,
        "cloud_extraction_connected": cloud_extraction_ok,
        "local_extraction_enabled": LOCAL_EXTRACTION_ENABLED,
        "extraction": extraction_mode_summary(),
        "sync": get_sync_stats(),
        "background_sync": {
            "enabled": SYNC_BACKGROUND_ENABLED,
            "interval_seconds": SYNC_BACKGROUND_INTERVAL_SECONDS,
        },
    }


@app.post("/scan")
async def scan_medicine(req: ScanRequest):
    images = [image.model_dump() for image in (req.images or [])]
    if not images and req.image_base64:
        images = [{"image_base64": req.image_base64, "image_filename": req.image_filename, "panel_name": "Image 1", "panel_type": "other"}]
    if not images:
        raise HTTPException(400, "Provide at least one image")

    ocr_results = []
    try:
        for index, image in enumerate(images, start=1):
            ocr_result = run_ocr_on_image(image["image_base64"])
            ocr_result["panel_name"] = image.get("panel_name") or f"Image {index}"
            ocr_result["panel_type"] = image.get("panel_type") or "other"
            if ocr_result["text"].strip():
                ocr_results.append(ocr_result)
    except ImportError:
        raise HTTPException(500, "OCR dependency is not installed. Install backend requirements first.")
    except Exception as exc:
        raise HTTPException(500, f"OCR failed: {str(exc)}")

    if not ocr_results:
        raise HTTPException(422, "No readable text detected in the image")
    ocr = combine_ocr_results(ocr_results)

    try:
        extraction_result = await extract_structured_fields(ocr["text"])
        extraction_result = apply_panel_aware_overrides(extraction_result, ocr)
    except httpx.ConnectError:
        raise HTTPException(502, "Cannot reach Ollama. Is it running? Run: ollama serve")
    except httpx.HTTPStatusError as exc:
        raise HTTPException(502, f"Extraction provider error: {exc.response.text}")
    except Exception as exc:
        raise HTTPException(500, f"Extraction failed: {str(exc)}")

    try:
        saved_images = save_images_locally(images)
    except Exception as exc:
        raise HTTPException(500, f"Failed to save images locally: {str(exc)}")

    return {
        "draft": build_draft(extraction_result, ocr, saved_images),
        "ocr": ocr,
        "extracted": extraction_result.get("extracted", {}),
        "extraction_provider": extraction_result.get("provider"),
        "field_meta": extraction_result.get("field_meta", {}),
        "review_hints": extraction_result.get("review_hints", []),
        "extraction": extraction_mode_summary(),
        "images": saved_images,
        "duplicates": find_duplicate_candidates(extraction_result.get("extracted", {})),
    }


@app.post("/medicines")
async def create_medicine(medicine: MedicineCreate):
    now = utc_now()
    record = {
        "scanned_at": medicine.scanned_at or now,
        "brand_name": medicine.brand_name,
        "generic_name": medicine.generic_name,
        "manufacturer": medicine.manufacturer,
        "batch_number": medicine.batch_number,
        "serial_number": medicine.serial_number,
        "dosage_form": medicine.dosage_form,
        "strength": medicine.strength,
        "quantity": medicine.quantity,
        "manufacture_date": medicine.manufacture_date,
        "expiry_date": medicine.expiry_date,
        "license_number": medicine.license_number,
        "barcode": medicine.barcode,
        "indications": medicine.indications,
        "warnings": medicine.warnings,
        "storage_info": medicine.storage_info,
        "active_ingredients": medicine.active_ingredients,
        "raw_extracted": medicine.raw_extracted,
        "confidence": medicine.confidence or "unknown",
        "image_filename": medicine.image_filename,
        "image_path": medicine.image_path,
        "sync_status": "pending_sync" if USE_SUPABASE_SYNC else "local_only",
        "last_synced_at": None,
        "updated_at": now,
    }
    duplicates = find_duplicate_candidates(record)
    saved = sqlite_insert(record)
    if USE_SUPABASE_SYNC:
        saved = queue_upsert_for_record(saved)
    sync_result = await run_sync(limit=10) if USE_SUPABASE_SYNC else None
    saved = sqlite_get(saved["id"]) or saved
    return {"id": saved.get("id"), "record": saved, "sync": sync_result, "duplicates": duplicates}


@app.get("/medicines")
async def list_medicines(limit: int = 50, offset: int = 0, search: str = ""):
    rows, total = sqlite_select(search, limit, offset)
    return {"total": total, "medicines": rows}


@app.get("/medicines/{medicine_id}")
async def get_medicine(medicine_id: int):
    row = sqlite_get(medicine_id)
    if not row:
        raise HTTPException(404, "Medicine not found")
    return row


@app.get("/medicines/{medicine_id}/history")
async def get_medicine_history_route(medicine_id: int, limit: int = 50):
    row = sqlite_get(medicine_id)
    if not row:
        raise HTTPException(404, "Medicine not found")
    return {"medicine_id": medicine_id, "history": get_medicine_history(medicine_id, limit=limit)}


@app.patch("/medicines/{medicine_id}")
async def update_medicine(medicine_id: int, update: MedicineUpdate):
    fields = {k: v for k, v in update.model_dump().items() if v is not None}
    if not fields:
        raise HTTPException(400, "No fields to update")
    updated = sqlite_update(medicine_id, fields)
    if not updated:
        raise HTTPException(404, "Medicine not found")
    refreshed = updated["record"]
    if USE_SUPABASE_SYNC:
        conn = get_conn()
        queue_sync(conn, medicine_id, "upsert", medicine_to_supabase_payload(refreshed))
        conn.commit()
        conn.close()
    sync_result = await run_sync(limit=10) if USE_SUPABASE_SYNC else None
    duplicates = find_duplicate_candidates(refreshed, exclude_id=medicine_id)
    return {
        "updated": True,
        "record": refreshed,
        "history_entry": updated["history_entry"],
        "duplicates": duplicates,
        "sync": sync_result,
    }


@app.delete("/medicines/{medicine_id}")
async def delete_medicine(medicine_id: int):
    deleted = sqlite_delete(medicine_id)
    if not deleted:
        raise HTTPException(404, "Medicine not found")
    sync_result = await run_sync(limit=10) if USE_SUPABASE_SYNC else None
    return {"deleted": True, "record": deleted["record"], "history_entry": deleted["history_entry"], "sync": sync_result}


@app.get("/sync/status")
async def sync_status():
    return {"stats": get_sync_stats(), "queue": list_sync_queue_items(limit=20)}


@app.get("/export/json")
async def export_json():
    return export_records_payload()


@app.get("/export/csv", response_class=PlainTextResponse)
async def export_csv():
    return export_records_csv()


@app.post("/import/json")
async def import_json(payload: ImportPayload):
    result = import_records(
        [record.model_dump() for record in payload.records],
        skip_possible_duplicates=payload.skip_possible_duplicates,
        note=payload.note,
    )
    sync_result = await run_sync(limit=10) if USE_SUPABASE_SYNC else None
    return {
        "imported_count": len(result["imported"]),
        "skipped_count": len(result["skipped"]),
        "imported": result["imported"],
        "skipped": result["skipped"],
        "sync": sync_result,
    }


@app.post("/backup/create")
async def create_backup():
    return create_sqlite_backup()


@app.post("/backup/bundle")
async def create_bundle():
    return create_bundle_backup()


@app.post("/backup/restore")
async def restore_bundle(payload: RestoreBundlePayload):
    try:
        return restore_bundle_backup(payload.archive_base64, payload.archive_filename or "mediscan-bundle.zip")
    except ValueError as exc:
        raise HTTPException(400, str(exc))
    except Exception as exc:
        raise HTTPException(500, f"Bundle restore failed: {str(exc)}")


@app.post("/sync/run")
async def sync_now(limit: int = 25):
    result = await run_sync(limit=limit)
    return {"result": result, "sync": get_sync_stats()}
