# Reference Images

Use this folder for real medicine-package images that we use for evaluation and tuning.

Recommended layout:

```text
references/
  raw/
  grouped/
  notes/
```

Guidelines:

- Put untouched original phone photos in `raw/`
- Put medicine-specific sets in `grouped/<medicine-name>/`
- Put notes, labels, or spreadsheets in `notes/`

Suggested grouped example:

```text
references/
  grouped/
    napa/
      front.jpg
      back.jpg
      side-expiry.jpg
    seclo/
      front.jpg
      back.jpg
```

Suggested naming:

- `front.jpg`
- `back.jpg`
- `side-left.jpg`
- `side-right.jpg`
- `top-batch.jpg`
- `blister.jpg`

If a file has a known issue, add it to the name:

- `front-blur.jpg`
- `back-glare.jpg`
- `expiry-lowlight.jpg`

Keep any private or sensitive notes in `notes/`, for example:

- expected brand name
- expected generic name
- visible batch number
- visible expiry date
- OCR difficulties

Evaluation workflow:

- add raw images into `references/raw/`
- fill expected values in `notes/reference-labels-template.csv`
- run the evaluation tool from `apps/local-backend/`

```bash
python tools/evaluate_references.py
```

Outputs:

- `references/notes/eval-report.json`
- `references/notes/eval-report.md`
