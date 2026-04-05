# Gemma Local Evaluation

This note captures how we should evaluate Gemma-style local models for the Android app without destabilizing the current offline workflow.

## Short Recommendation

Do not replace the current Android pipeline yet.

Current production-safe path:

- Camera capture on device
- ML Kit OCR on device
- Rule-based extraction and medicine vocabulary correction
- Review before save
- Room / SQLite local persistence

Recommended Gemma role:

- optional local assist for low-confidence cases
- optional second-pass normalization for mixed Bengali + English packaging
- optional multi-panel reasoning over OCR text

## Why Not Replace OCR Immediately

For this app, OCR and extraction are not the same problem.

ML Kit is currently giving us:

- fast on-device text recognition
- low integration complexity
- predictable battery and latency behavior
- a small and practical offline footprint

Gemma may help with:

- noisy brand/generic cleanup
- mixed-script packaging
- reasoning across packet detail side and strip together

Gemma is still risky as the primary OCR layer because:

- Android runtime cost can be high
- performance varies a lot across devices
- app size and model packaging become more complex
- deterministic fields like batch, expiry, and license often still benefit from rules

## What Official Google Guidance Suggests

The current official on-device Google path is centered around Google AI Edge, MediaPipe / LiteRT, and Gemma 3n-style mobile inference, not a direct recommendation to replace mobile OCR with a large vision model.

Useful references:

- Google AI Edge Gallery / Gemma 3n on-device announcement:
  https://developers.googleblog.com/en/google-ai-edge-gallery-now-with-audio-and-on-google-play/
- Google AI Edge Android documentation:
  https://ai.google.dev/edge
- Android RAG sample showing Gemma on device:
  https://ai.google.dev/edge/mediapipe/solutions/genai/rag/android

This is an inference from the docs:

- Google clearly supports on-device Gemma-style use on Android
- the clearest mobile-ready path today appears to be Gemma 3n / AI Edge tooling
- we should treat "Gemma 4" as a candidate only after validating the exact runtime, model size, and Android support story we intend to use

## Recommended Architecture

Preferred hybrid-offline pipeline on phone:

1. Capture packet date side, packet detail side, and strip
2. Run ML Kit OCR per panel
3. Run current rule-based extractor
4. If confidence is low, run Gemma assist locally
5. Show the reviewed draft
6. Save locally to Room

That keeps the deterministic base path intact while letting Gemma improve only the hard cases.

## Best Candidate Tasks For Gemma

Use Gemma for:

- brand correction from noisy OCR text
- generic correction on bilingual packaging
- selecting the best brand/generic candidate across multiple OCR lines
- synthesizing a draft from multiple panels when the rule path is low confidence

Do not rely on Gemma first for:

- batch number extraction
- manufacture date extraction
- expiry date extraction
- license number extraction

Those should remain rule-first because they are operational fields and we want stable behavior.

## Evaluation Plan

Before integrating any model, run a small offline benchmark.

Dataset:

- 20 to 30 difficult medicine examples
- especially mixed Bengali + English packs
- include expected brand and generic labels

Compare:

1. ML Kit OCR + current Android rules
2. ML Kit OCR + Gemma assist
3. if feasible later, direct vision prompt into Gemma on-device

Measure:

- brand accuracy
- generic accuracy
- average draft time per medicine
- memory impact
- battery / thermal impact during repeated scans

Go / no-go rule:

- Gemma should materially improve hard-case brand/generic accuracy
- latency should remain usable on the real target phones
- the app should remain fully offline and stable

## Integration Options

Lowest-risk option:

- keep Gemma outside the critical path
- invoke it only when current confidence is low

Medium-risk option:

- always use Gemma to re-rank brand and generic after OCR

Highest-risk option:

- use Gemma as primary multimodal extractor

The lowest-risk option is the one we should prototype first.

## Next Steps

1. Keep the current ML Kit + rules pipeline as the default offline path
2. Build a labeled hard-case dataset from your failing medicines
3. Prototype a Gemma assist module behind a feature flag
4. Compare accuracy and latency on your actual Android device
5. Adopt it only if it clearly helps the mixed-language cases
