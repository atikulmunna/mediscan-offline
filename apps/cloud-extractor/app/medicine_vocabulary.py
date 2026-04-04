from difflib import SequenceMatcher
import re


BRAND_ALIASES = {
    "napaone": "NapaOne",
    "napa one": "NapaOne",
    "napaoone": "NapaOne",
    "napaonee": "NapaOne",
    "napadone": "NapaOne",
    "rupa": "Rupa",
    "exium20": "Exium20",
    "exium 20": "Exium20",
    "napa extra": "Napa Extra",
    "napaextra": "Napa Extra",
    "extra": "Napa Extra",
    "trilock10": "Trilock10",
    "trilock 10": "Trilock10",
    "triloa": "Trilock10",
    "trilok10g": "Trilock10",
    "metroni": "Filmet400",
    "metrorid": "Filmet400",
    "filmet400": "Filmet400",
    "filmer400": "Filmet400",
    "norium10": "Norium 10",
    "norium1": "Norium 10",
    "norium1#": "Norium 10",
    "norium": "Norium 10",
    "oriup": "Norium 10",
    "noriup": "Norium 10",
    "coralcal-dx": "Coralcal-DX",
    "coralcal dx": "Coralcal-DX",
    "fluclox": "Fluclox",
    "cranbiotic": "Cranbiotic",
    "filfresh": "Filfresh",
    "filfresh'": "Filfresh",
}

BRAND_CANONICAL = sorted(set(BRAND_ALIASES.values()))

GENERIC_CANONICAL = {
    "paracetamol": "Paracetamol",
    "esomeprazole": "Esomeprazole",
    "rupatadine": "Rupatadine",
    "metronidazole": "Metronidazole",
    "melatonin": "Melatonin",
    "flucloxacillin": "Flucloxacillin",
    "montelukast": "Montelukast",
    "flunarizine": "Flunarizine",
    "cranberry": "Cranberry",
    "calcium": "Calcium",
    "caffeine": "Caffeine",
}


def normalize_token(value: str | None) -> str:
    return re.sub(r"[^a-z0-9]+", "", (value or "").strip().lower())


def correct_brand_name(value: str | None) -> str | None:
    if not value:
        return None
    lowered = value.strip().lower()
    compact = normalize_token(value)

    for alias, canonical in BRAND_ALIASES.items():
        if lowered == alias or compact == normalize_token(alias):
            return canonical

    best_match = None
    best_score = 0.0
    for alias, canonical in BRAND_ALIASES.items():
        score = SequenceMatcher(None, compact, normalize_token(alias)).ratio()
        if score > best_score:
            best_score = score
            best_match = canonical
    if best_match and best_score >= 0.82:
        return best_match
    return value


def correct_generic_line(value: str | None) -> str | None:
    if not value:
        return None

    corrected = re.sub(r"(\d+)\.m\b", r"\1 mg", value, flags=re.IGNORECASE)
    lowered = value.lower()
    for token, canonical in GENERIC_CANONICAL.items():
        variants = {token}
        compact = normalize_token(token)
        for word in re.findall(r"[a-z]+", lowered):
            if SequenceMatcher(None, normalize_token(word), compact).ratio() >= 0.8:
                variants.add(word)
        for variant in sorted(variants, key=len, reverse=True):
            corrected = re.sub(rf"\b{re.escape(variant)}\b", canonical, corrected, flags=re.IGNORECASE)

    corrected = re.sub(r"\s+", " ", corrected).strip()
    corrected = corrected.replace("&", "").strip()
    return corrected or value
