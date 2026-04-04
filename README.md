# MediScan

Medicine package scanning monorepo with:

- a research/prototype stack for OCR, extraction, and evaluation
- a native Android product path for the client's `offline on phone only` requirement

## Repo Status

This repository now has two tracks:

- `prototype track`
  `apps/mobile-web`, `apps/local-backend`, and `apps/cloud-extractor`
- `product track`
  `apps/android`

The prototype track is still useful for:

- OCR experiments
- extraction-rule iteration
- reference-image evaluation
- desktop-assisted validation

The Android track is the long-term delivery path for the client requirement.

## Recommended Reading Order

- Start with [apps/android/README.md](/c:/Users/Munna/Documents/GitSync/medicine-scanner/apps/android/README.md) if you care about the final offline product direction.
- Use the rest of this README for the prototype stack, evaluation workflow, and current research tooling.

Current workflow:

- take one or more photos from your phone
- run OCR on the package text
- prefer cloud extraction in hybrid mode, with local extraction fallback
- review and correct the draft manually
- save the reviewed record into local SQLite
- optionally sync local records to Supabase

## Why This Shape

Many medicine packages do not carry usable barcodes, and even when they do, the barcode often does not contain the full operational data you care about. Because of that, this scaffold now treats OCR plus human review as the core workflow.

The current direction is hybrid:

- local capture
- local draft/review flow
- local SQLite cache
- cloud extraction when available
- local fallback when cloud extraction is unavailable
- optional cloud sync

## Architecture

```text
Phone camera / image upload
        ->
mobile web client
        ->
local backend service
        ->
OCR
        ->
cloud extraction API or local LLaVA fallback
        ->
review screen
        ->
SQLite on this machine
        ->
optional Supabase sync queue
```

Local assets:

- records in `apps/local-backend/medicines.db`
- captured images in `apps/local-backend/scans/`

## Current Product Flow

The scan flow is now:

1. capture or upload one or more package images
2. tag each image as `packet_date_side`, `packet_detail_side`, `strip`, or `other`
3. preprocess each panel for OCR
4. combine OCR across the captured panels
5. normalize OCR text into structured medicine fields
6. apply panel-aware field priority before review
7. show a review form before saving
8. save only after user confirmation

This is safer than direct-save extraction, especially for:

- batch numbers
- expiry dates
- manufacturer text
- license numbers
- active ingredient strings

It is also better for packages where:

- front and back panels split the information
- batch and expiry are printed on a side flap
- manufacturer and composition are not visible in the same photo

The current capture model is optimized for medicine packets like:

- `packet_date_side`: batch number, manufacture date, expiry date
- `packet_detail_side`: brand name, strength, generic name, pack quantity
- `strip`: brand name, strength, generic name

## Runtime Modes

The backend now supports three extraction modes:

- `APP_MODE=hybrid`
  cloud extraction first, local LLaVA fallback
- `APP_MODE=local`
  local OCR + local LLaVA only
- `APP_MODE=cloud`
  cloud extraction only

Recommended mode:

`APP_MODE=hybrid`

## Tech Stack

- Frontend: React + Vite
- Backend: FastAPI
- OCR: `rapidocr_onnxruntime` primary, `paddleocr` optional fallback
- image preprocessing: Pillow
- Local extraction fallback: Ollama + `llava`
- Cloud extraction: configurable HTTP provider
- Primary storage: SQLite
- Optional replication: Supabase

## Project Structure

```text
apps/
  android/
    app/
    README.md
  mobile-web/
    src/App.jsx
  local-backend/
    main.py
    requirements.txt
    medicines.db
    scans/
    app/
      api/
      core/
      db/
      services/
  cloud-extractor/
    main.py
    requirements.txt
    app/
references/
  raw/
  notes/
```

## Prerequisites

- Python 3.10+
- Node.js 18+
- Ollama

Install Ollama:

`https://ollama.com`

## Setup

### 1. Pull the local fallback model

```bash
ollama pull llava
```

Optional larger fallback models:

```bash
ollama pull llava:13b
ollama pull llava:34b
```

### 2. Start Ollama for fallback extraction

```bash
ollama serve
```

### 3. Start the backend

```bash
cd apps/local-backend
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

Backend endpoints:

- API: `http://localhost:8000`
- Docs: `http://localhost:8000/docs`

### 4. Start the frontend

```bash
cd apps/mobile-web
npm install
npm run dev
```

Frontend:

- App: `http://localhost:5173`

### 5. Start the cloud extractor for hybrid mode

```bash
cd apps/cloud-extractor
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8100 --reload
```

Cloud extractor:

- API: `http://localhost:8100`
- Health: `http://localhost:8100/health`
- Extract: `http://localhost:8100/extract`

### 6. Open it on your phone

Make sure your phone and computer are on the same network, then open:

```text
http://<your-computer-ip>:5173
```

The frontend now derives the backend host from the page host, so phone requests point to `http://<your-computer-ip>:8000`.

Important mobile note:

- camera upload from a phone browser usually works only in a secure context
- `http://localhost` is treated as secure, but `http://<your-computer-ip>:5173` usually is not
- on mobile over LAN HTTP, use the Upload flow unless you add HTTPS to the dev server

## Hybrid Configuration

Create `apps/local-backend/.env`:

```env
APP_MODE=hybrid

# local fallback extraction
OLLAMA_MODEL=llava
LOCAL_EXTRACTION_ENABLED=true

# cloud extraction
CLOUD_EXTRACT_URL=http://localhost:8100/extract
CLOUD_EXTRACT_API_KEY=your_api_key

# optional sync
SUPABASE_URL=https://your-project.supabase.co
SUPABASE_KEY=your_service_role_key
SUPABASE_TABLE=medicines
```

What this means:

- the phone-facing app still saves reviewed records locally
- the backend prefers cloud extraction for better APK-friendly architecture
- if the cloud extractor is unavailable, the backend can fall back to local LLaVA

The included `apps/cloud-extractor` app is the current cloud normalization service for the hybrid path.
It now supports a hosted LLM-backed normalizer with rule-based fallback, and returns:

- field-level review metadata
- evidence lines from OCR text where possible
- explicit review hints for sensitive fields like batch and expiry

Example `apps/cloud-extractor/.env`:

```env
PROVIDER_MODE=hybrid
LLM_API_URL=https://api.openai.com/v1/chat/completions
LLM_API_KEY=your_hosted_llm_api_key
LLM_MODEL=gpt-4.1-mini
LLM_TIMEOUT_SECONDS=45
LLM_TEMPERATURE=0
```

## Optional Supabase Sync

Supabase is optional replication only. Local SQLite remains the source of truth.

Create the Supabase table:

```sql
create table if not exists medicines (
  id bigint generated always as identity primary key,
  local_id bigint unique not null,
  scanned_at timestamptz not null default now(),
  brand_name text,
  generic_name text,
  manufacturer text,
  batch_number text,
  serial_number text,
  dosage_form text,
  strength text,
  quantity text,
  manufacture_date text,
  expiry_date text,
  license_number text,
  barcode text,
  indications text,
  warnings text,
  storage_info text,
  active_ingredients text,
  raw_extracted text,
  confidence text,
  image_filename text,
  image_path text,
  local_updated_at timestamptz,
  last_synced_at timestamptz
);

alter table medicines disable row level security;
```

Restart the backend after saving `apps/local-backend/.env`.

## Why This Structure Is Better For Hybrid Android Development

The repo now separates the two app surfaces explicitly:

- `apps/mobile-web` is the phone-facing prototype client
- `apps/local-backend` is the local cache, review, OCR, fallback extraction, sync, and API service

That makes it easier to add a future `apps/android/` folder without rewriting the rest of the project shape.

The backend is also split by responsibility:

- `app/api/` for routes
- `app/core/` for configuration
- `app/db/` for schema and SQLite setup
- `app/services/` for OCR, extraction, sync, and persistence logic

This is a much better base if we later replace the web client with:

- a native Kotlin Android app
- a React Native app
- a Capacitor wrapper around the current mobile web app

It also lets us move toward a real hybrid deployment:

- Android app handles capture, review, and local cache
- cloud service handles heavy extraction
- local backend stays useful as a dev runtime and fallback path

## API Endpoints

Core endpoints:

- `GET /health`
- `POST /scan`
- `POST /medicines`
- `GET /medicines`
- `GET /medicines/{id}`
- `GET /medicines/{id}/history`
- `PATCH /medicines/{id}`
- `DELETE /medicines/{id}`
- `GET /export/json`
- `GET /export/csv`
- `POST /import/json`
- `POST /backup/create`

Sync endpoints:

- `GET /sync/status`
- `POST /sync/run`

Cloud extraction endpoints:

- `GET /health`
- `POST /extract`

## Scan Response Model

`POST /scan` no longer writes directly to the medicine table.

It returns:

- OCR text
- normalized extracted fields
- field-level review metadata
- panel-aware field sources when a value was chosen from a specific packet side or strip
- review hints
- saved image metadata for all captured panels
- preprocessing metadata for each panel
- duplicate candidates based on brand, generic, strength, batch, expiry, and barcode
- a draft payload including local image path

The frontend review screen edits that draft and then sends it to `POST /medicines`.

## Stored Fields

Saved medicine records include:

- `brand_name`
- `generic_name`
- `manufacturer`
- `batch_number`
- `serial_number`
- `dosage_form`
- `strength`
- `quantity`
- `manufacture_date`
- `expiry_date`
- `license_number`
- `barcode`
- `indications`
- `warnings`
- `storage_info`
- `active_ingredients`
- `raw_extracted`
- `confidence`
- `image_filename`
- `image_path`
- `sync_status`
- `last_synced_at`
- `updated_at`

Local audit entries now track:

- create events
- post-save edits
- delete events
- changed field snapshots
- optional edit notes from the reviewer

Local portability tools now include:

- JSON export with records plus audit log
- CSV export for spreadsheet workflows
- JSON import with duplicate skipping
- one-click SQLite backup creation
- bundle ZIP backup with SQLite plus captured scans
- bundle restore with automatic safety backup

## Local-First Behavior

If Supabase is unavailable:

- scans still work
- OCR still works
- review still works
- records still save locally
- images still save locally
- sync jobs remain queued
- failed sync jobs retry later until they reach the dead-letter threshold

## Practical Notes

### OCR is the primary source now

For countries and supply chains where packaging is text-heavy and barcode-light, OCR is the right default. The normalization model should be treated as a helper that turns raw package text into cleaner structure.

### Preprocessing now runs before OCR

Each captured panel is currently passed through a conservative preprocessing pipeline before OCR:

- EXIF orientation normalization
- grayscale conversion
- autocontrast
- upscaling for small images
- light sharpening

The review screen shows per-panel preprocessing metadata so we can debug extraction quality without guessing what happened to the image.

### OCR runtime now prefers the more stable Windows path

The local backend now prefers `rapidocr_onnxruntime` when it is installed, and falls back to `paddleocr` if needed.

This matters because:

- the current Windows Paddle stack can be unstable or hang on some machines
- RapidOCR has been more reliable for local evaluation in this scaffold
- the OCR service still preserves the same output shape for the rest of the pipeline

### Duplicate detection now runs before save

The backend now checks for likely duplicate records during scan review and again during save. The current heuristic favors:

- same barcode
- same brand and batch
- same generic, strength, and manufacturer
- same brand and strength
- same batch and expiry

These are warnings, not hard blocks, so the user can still save when a package is genuinely a separate unit or a corrected re-entry.

### Post-save editing and audit history are now built in

Saved records can now be opened from the Records tab and:

- reviewed in a detail view
- edited after save
- saved with an optional audit note
- inspected through a per-record history timeline

This makes it safer to correct OCR mistakes after the initial save without losing traceability.

### Export, import, and backup are now available

The Status tab now exposes local portability tools for operational safety:

- export the current local data as JSON
- export a flat CSV for spreadsheet review
- import records from a previous JSON export
- create a timestamped SQLite backup under `apps/local-backend/backups/`
- create a ZIP bundle backup that includes both `medicines.db` and `scans/`
- restore local state from a bundle ZIP after taking an automatic safety backup

The JSON export includes both medicine records and the audit log. Import currently restores records only, not the historical audit timeline, and it skips likely duplicates by default.
Bundle restore is intended for machine recovery or transferring the local workspace, because it restores the captured image files alongside the SQLite database.

### Sync is now more durable

The sync queue now supports:

- retry delays between failed attempts
- a max-attempt cap before a job moves to dead-letter status
- next-attempt scheduling metadata
- queue visibility from the Status tab
- optional background sync polling when Supabase sync is configured

You can tune it in `apps/local-backend/.env` with:

```env
SYNC_MAX_ATTEMPTS=5
SYNC_RETRY_DELAY_SECONDS=300
SYNC_BACKGROUND_INTERVAL_SECONDS=120
```

When background sync is enabled, the local backend will periodically try to drain the queue even if nobody presses the manual sync button.

### Hybrid is now the preferred deployment model

For the product we want to ship, hybrid gives the best balance:

- easier eventual APK path
- better extraction quality than pure on-device rules alone
- local caching and review remain available
- we are no longer forced to run heavy inference on the phone

### The included cloud extractor is now LLM-ready

The cloud extraction service can now run in three modes:

- `PROVIDER_MODE=rules`
- `PROVIDER_MODE=llm`
- `PROVIDER_MODE=hybrid`

Recommended:

- `PROVIDER_MODE=hybrid`

That lets the cloud extractor prefer a hosted LLM normalizer when configured, but still fall back to the deterministic rules engine if the hosted provider is unavailable.

### Review is not optional in spirit

The app allows you to save only after review because medicine metadata is operationally sensitive. Users should verify:

- expiry date
- batch number
- dosage strength
- manufacturer
- warnings

### Barcode support is still worth adding later

Even if many local medicines do not carry barcodes, a barcode decoder can still help for the subset that does. It should be added as a secondary enrichment path, not the primary one.

## Best Practices For Better Results

- use bright, even lighting
- fill the frame with the package panel
- avoid blur and angled shots
- scan multiple sides when details are split across panels
- verify lot, expiry, and strength before saving
- create periodic local backups before bulk imports
- prefer bundle backups when you want a restorable copy of both records and scan images

## Reference Evaluation

You can now evaluate real package images from `references/raw/` using:

```bash
cd apps/local-backend
python tools/evaluate_references.py
```

This writes:

- `references/notes/eval-report.json`
- `references/notes/eval-report.md`

Use `references/notes/reference-labels-template.csv` to record expected values for each image so we can compare extraction quality against real package data.
- review dead-letter sync jobs in the Status tab if cloud replication stays down

## Suggested Next Work Items

- improve rule coverage for batch, expiry, quantity, and manufacturer extraction
- compare rule mode vs hosted LLM mode on a larger reference-image set
- add barcode decoding as an optional secondary step
- add expected-label comparison into the reference evaluation report

## Android Pivot

The client requirement has now shifted to `full offline on phone only`.

Because of that, the long-term target architecture is no longer:

- mobile web client
- local backend on another machine
- optional cloud extractor

The recommended product architecture is now:

- native Android app
- on-device OCR
- on-device SQLite / Room
- rule-based extraction plus medicine vocabulary correction
- review-before-save
- optional sync later, but not required for core use

An initial Android scaffold now lives in:

- [apps/android](C:/Users/Munna/Documents/GitSync/medicine-scanner/apps/android)

That Android app should become the real product path, while the existing web/backend stack remains useful for:

- OCR and extraction research
- reference-image evaluation
- rules and vocabulary iteration
- desktop-assisted prototyping

## GitHub Recommendation

This repository is intentionally a monorepo for now.

Use it as:

- one repo
- separate app folders
- separate issue labels and milestones for `prototype` and `android`
- small scoped commits instead of mixing unrelated changes

It is okay to keep both tracks together until:

- Android becomes the only maintained product
- the prototype stack stops adding value
- or separate teams need separate release cycles

## Testing Gate For Future Modules

Before we move to the next module, we should always pass this minimum gate:

1. backend compile checks pass
2. cloud extractor compile checks pass
3. mobile web production build passes
4. backend API smoke tests pass
5. cloud extractor tests pass
6. the specific feature added in the current module is manually sanity-checked

Current automated smoke tests live in:

- `apps/local-backend/tests/test_api.py`
- `apps/cloud-extractor/tests/test_extractor.py`

Recommended commands:

```bash
cd apps/local-backend
python -m unittest discover -s tests

cd ../cloud-extractor
python -m unittest discover -s tests

cd ../mobile-web
npm run build
```

This should be our standard before we say a module is done and start the next one.
