import json
from pathlib import Path


SUPPORTED_IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".webp"}


def discover_reference_images(reference_dir: Path) -> list[Path]:
    if not reference_dir.exists():
        return []
    return sorted(
        path
        for path in reference_dir.iterdir()
        if path.is_file() and path.suffix.lower() in SUPPORTED_IMAGE_SUFFIXES
    )


def summarize_evaluation(results: list[dict]) -> dict:
    processed = [result for result in results if result.get("status") == "ok"]
    failed = [result for result in results if result.get("status") != "ok"]

    provider_counts = {}
    confidence_counts = {}
    field_coverage = {}

    for result in processed:
        provider = result.get("provider") or "unknown"
        provider_counts[provider] = provider_counts.get(provider, 0) + 1

        confidence = result.get("confidence") or "unknown"
        confidence_counts[confidence] = confidence_counts.get(confidence, 0) + 1

        for field, value in (result.get("extracted") or {}).items():
            if field == "confidence":
                continue
            if field not in field_coverage:
                field_coverage[field] = {"present": 0, "missing": 0}
            if value:
                field_coverage[field]["present"] += 1
            else:
                field_coverage[field]["missing"] += 1

    return {
        "total_images": len(results),
        "processed_images": len(processed),
        "failed_images": len(failed),
        "provider_counts": provider_counts,
        "confidence_counts": confidence_counts,
        "field_coverage": field_coverage,
    }


def build_markdown_report(report: dict) -> str:
    summary = report.get("summary", {})
    results = report.get("results", [])

    lines = [
        "# Reference Evaluation Report",
        "",
        f"- generated_at: {report.get('generated_at')}",
        f"- reference_dir: {report.get('reference_dir')}",
        f"- total_images: {summary.get('total_images', 0)}",
        f"- processed_images: {summary.get('processed_images', 0)}",
        f"- failed_images: {summary.get('failed_images', 0)}",
        "",
        "## Provider Counts",
        "",
    ]

    provider_counts = summary.get("provider_counts", {})
    if provider_counts:
        for provider, count in provider_counts.items():
            lines.append(f"- {provider}: {count}")
    else:
        lines.append("- none")

    lines.extend(["", "## Confidence Counts", ""])
    confidence_counts = summary.get("confidence_counts", {})
    if confidence_counts:
        for confidence, count in confidence_counts.items():
            lines.append(f"- {confidence}: {count}")
    else:
        lines.append("- none")

    lines.extend(["", "## Field Coverage", ""])
    field_coverage = summary.get("field_coverage", {})
    if field_coverage:
        for field, stats in sorted(field_coverage.items()):
            lines.append(f"- {field}: present {stats.get('present', 0)}, missing {stats.get('missing', 0)}")
    else:
        lines.append("- none")

    lines.extend(["", "## Per Image", ""])
    for result in results:
        lines.append(f"### {result.get('filename')}")
        lines.append(f"- status: {result.get('status')}")
        if result.get("provider"):
            lines.append(f"- provider: {result.get('provider')}")
        if result.get("confidence"):
            lines.append(f"- confidence: {result.get('confidence')}")
        if result.get("error"):
            lines.append(f"- error: {result.get('error')}")
        if result.get("review_hints"):
            lines.append("- review_hints:")
            for hint in result["review_hints"]:
                lines.append(f"  - {hint}")
        extracted = result.get("extracted") or {}
        if extracted:
            lines.append("- extracted:")
            for field, value in extracted.items():
                lines.append(f"  - {field}: {json.dumps(value, ensure_ascii=True)}")
        lines.append("")

    return "\n".join(lines).rstrip() + "\n"
