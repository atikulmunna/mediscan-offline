# MediScan

Medicine package OCR and offline scanning monorepo.

This repo contains two tracks:

- `apps/android`: the real product direction for the client's `offline on phone only` requirement
- `apps/mobile-web`, `apps/local-backend`, `apps/cloud-extractor`: the prototype and evaluation stack used to explore OCR, extraction rules, and workflow design

## Choose Your Track

- Start with [apps/android/README.md](apps/android/README.md) if you care about the final Android product.
- Use the rest of this README if you want to run the current prototype stack or evaluate reference images.

## Repository Status

The long-term target is a native Android app that runs the core workflow fully offline on the phone:

- camera capture
- OCR
- extraction
- review before save
- local storage

The web and backend apps remain useful as research tooling for:

- OCR experiments
- extraction-rule iteration
- reference-image evaluation
- desktop-assisted validation

## Monorepo Layout

```text
apps/
  android/           # native Android offline product path
  mobile-web/        # phone-facing prototype UI
  local-backend/     # OCR, draft/review, SQLite, export/import, sync experiments
  cloud-extractor/   # hosted normalization prototype
references/
  raw/               # package images for evaluation
  notes/             # labels, reports, evaluation notes
```

## Android Product Direction

The Android app is the intended delivery path for the client's requirement.

Planned stack:

- Kotlin
- Jetpack Compose
- Room / SQLite
- on-device OCR
- rule-based extraction plus local medicine dictionaries

Guided capture model:

- `packet_date_side`
- `packet_detail_side`
- `strip`

That matches the packaging structure discovered during evaluation:

- date side: batch, manufacture date, expiry, license
- detail side: brand, generic, strength, quantity
- strip: brand, generic, strength

See [apps/android/README.md](apps/android/README.md) for Android-specific status and next modules.

## Prototype Stack

The current prototype stack is still useful for learning and validation.

Current flow:

1. capture or upload one or more package images
2. tag each image as `packet_date_side`, `packet_detail_side`, `strip`, or `other`
3. preprocess each panel for OCR
4. combine OCR across the captured panels
5. normalize OCR text into structured medicine fields
6. review and correct the draft manually
7. save the reviewed record to local SQLite
8. optionally sync later

Prototype stack:

- frontend: React + Vite
- backend: FastAPI
- OCR: `rapidocr_onnxruntime` primary, `paddleocr` optional fallback
- preprocessing: Pillow
- local fallback extraction: Ollama + `llava`
- storage: SQLite
- optional replication: Supabase

## Running The Prototype

### Prerequisites

- Python 3.10+
- Node.js 18+
- Ollama if you want local fallback extraction

### Local backend

```bash
cd apps/local-backend
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

### Mobile web app

```bash
cd apps/mobile-web
npm install
npm run dev
```

### Optional cloud extractor

```bash
cd apps/cloud-extractor
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8100 --reload
```

### Optional local LLaVA fallback

```bash
ollama pull llava
ollama serve
```

## Configuration

Create `apps/local-backend/.env`:

```env
APP_MODE=hybrid

OLLAMA_MODEL=llava
LOCAL_EXTRACTION_ENABLED=true

CLOUD_EXTRACT_URL=http://localhost:8100/extract
CLOUD_EXTRACT_API_KEY=your_api_key

SUPABASE_URL=https://your-project.supabase.co
SUPABASE_KEY=your_service_role_key
SUPABASE_TABLE=medicines
```

Modes:

- `APP_MODE=hybrid`: cloud extraction first, local fallback second
- `APP_MODE=local`: fully local backend stack
- `APP_MODE=cloud`: cloud extraction only

## Mobile Testing Note

When opening the prototype from a phone over LAN:

- `http://localhost` is treated as secure by browsers
- `http://<your-computer-ip>:5173` usually is not

That means live camera access may not work on a mobile browser over plain LAN HTTP, even when file upload works.

## Reference Evaluation

Reference images live in `references/raw/`.

Run evaluation with:

```bash
cd apps/local-backend
python tools/evaluate_references.py
```

Outputs:

- `references/notes/eval-report.json`
- `references/notes/eval-report.md`

Use `references/notes/reference-labels-template.csv` to record expected values for each image.

## Testing Gate

Before closing a module, this minimum gate should pass:

1. backend tests
2. cloud extractor tests
3. Python compile checks
4. mobile web production build
5. targeted manual sanity check for the module

Recommended commands:

```bash
cd apps/local-backend
python -m unittest discover -s tests

cd ../cloud-extractor
python -m unittest discover -s tests

cd ../mobile-web
npm run build
```

## Why One Repo

This stays a monorepo for now because the prototype stack is still helping the Android product direction:

- it gives us a fast place to test OCR and extraction ideas
- it provides evaluation tooling against real package images
- it helps us learn the packaging patterns before we lock Android implementation details

If Android becomes the only maintained surface later, the prototype apps can be archived or split out.
