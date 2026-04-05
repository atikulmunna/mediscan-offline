# MediScan Android

Native Android pivot for the client's `offline on phone only` requirement.

Status:

- this is the intended product track
- the implementation is still early-stage
- the current web/backend apps in the repo are research tooling, not the final delivery architecture

This app is intended to become the real product, while the existing web and backend apps remain useful as:

- OCR and extraction research tooling
- evaluation tooling against reference images
- rule prototyping
- desktop-assisted experimentation

## Product Goal

Everything required for the core scan workflow should run on the Android device:

- camera capture
- OCR
- extraction
- local database
- review before save
- image storage

Internet access must not be required for the core workflow.

## Planned Stack

- Kotlin
- Jetpack Compose
- Room / SQLite
- ML Kit Text Recognition or another on-device OCR SDK
- rule-based extraction plus local medicine dictionaries

## Capture Model

The app is designed around three guided captures:

- `packet_date_side`
- `packet_detail_side`
- `strip`

Those map to the real packaging pattern discovered during evaluation:

- date side: batch, manufacture date, expiry, license
- detail side: brand, generic, strength, quantity
- strip: brand, generic, strength

## Current Status

This is an initial scaffold only.

What is included:

- Gradle project skeleton
- Compose guided capture flow
- camera permission and `TakePicture` capture wiring
- local file persistence for captured packet and strip panels
- per-panel preview and retake support in the guided flow
- bundled on-device OCR with ML Kit
- offline rule-based extraction draft generation
- review/edit form before local save
- Room database, DAO, and local save flow
- saved-medicines list wired to local SQLite
- post-save record detail/edit screen
- saved-medicines search and confidence filtering
- offline capture domain models
- Room entity skeleton
- extraction pipeline contract
- first-pass Android architecture notes

What is not done yet:

- duplicate detection on Android
- audit/history on Android
- export/import and backup on Android
- optional local Gemma-assist evaluation for hard bilingual OCR cases

## Next Android Modules

1. Duplicate detection on Android
2. Audit/history on Android
3. Export/import and backup on Android

## Research Notes

- Optional Gemma evaluation note: [docs/gemma-local-evaluation.md](./docs/gemma-local-evaluation.md)

## Experimental Hooks

- A feature-flagged local assist seam now exists in the Android extraction pipeline.
- The current flag is off by default, so the shipped behavior remains `ML Kit OCR -> rules -> review -> save`.
- This lets us plug in a future Gemma-style on-device helper without rewriting the extraction flow again.
- A Gemma-ready prompt builder now exists so low-confidence OCR cases can be packaged into a structured local-assist request later.
