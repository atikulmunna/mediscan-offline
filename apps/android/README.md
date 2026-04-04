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
- offline capture domain models
- Room entity skeleton
- extraction pipeline contract
- first-pass Android architecture notes

What is not done yet:

- OCR integration
- Room DAO/database wiring
- extraction logic port
- save/search/edit flows
- verified Android build and on-device capture test

## Next Android Modules

1. Verify the current camera flow on a real Android build or emulator
2. On-device OCR
3. Rule-based extraction and medicine vocabulary correction
4. Review/edit screen
5. Room persistence and search
