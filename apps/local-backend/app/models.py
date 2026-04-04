from typing import Optional

from pydantic import BaseModel


class ScanImageInput(BaseModel):
    image_base64: str
    image_filename: Optional[str] = "scan.jpg"
    panel_name: Optional[str] = None
    panel_type: Optional[str] = "other"


class ScanRequest(BaseModel):
    image_base64: Optional[str] = None
    image_filename: Optional[str] = "scan.jpg"
    images: list[ScanImageInput] | None = None


class MedicineCreate(BaseModel):
    scanned_at: Optional[str] = None
    brand_name: Optional[str] = None
    generic_name: Optional[str] = None
    manufacturer: Optional[str] = None
    batch_number: Optional[str] = None
    serial_number: Optional[str] = None
    dosage_form: Optional[str] = None
    strength: Optional[str] = None
    quantity: Optional[str] = None
    manufacture_date: Optional[str] = None
    expiry_date: Optional[str] = None
    license_number: Optional[str] = None
    barcode: Optional[str] = None
    indications: Optional[str] = None
    warnings: Optional[str] = None
    storage_info: Optional[str] = None
    active_ingredients: Optional[str] = None
    raw_extracted: Optional[str] = None
    confidence: Optional[str] = "unknown"
    image_filename: str
    image_path: str


class MedicineUpdate(BaseModel):
    scanned_at: Optional[str] = None
    brand_name: Optional[str] = None
    generic_name: Optional[str] = None
    manufacturer: Optional[str] = None
    batch_number: Optional[str] = None
    serial_number: Optional[str] = None
    dosage_form: Optional[str] = None
    expiry_date: Optional[str] = None
    strength: Optional[str] = None
    quantity: Optional[str] = None
    manufacture_date: Optional[str] = None
    license_number: Optional[str] = None
    barcode: Optional[str] = None
    indications: Optional[str] = None
    warnings: Optional[str] = None
    storage_info: Optional[str] = None
    active_ingredients: Optional[str] = None
    confidence: Optional[str] = None
    note: Optional[str] = None


class ImportRecord(BaseModel):
    scanned_at: Optional[str] = None
    brand_name: Optional[str] = None
    generic_name: Optional[str] = None
    manufacturer: Optional[str] = None
    batch_number: Optional[str] = None
    serial_number: Optional[str] = None
    dosage_form: Optional[str] = None
    strength: Optional[str] = None
    quantity: Optional[str] = None
    manufacture_date: Optional[str] = None
    expiry_date: Optional[str] = None
    license_number: Optional[str] = None
    barcode: Optional[str] = None
    indications: Optional[str] = None
    warnings: Optional[str] = None
    storage_info: Optional[str] = None
    active_ingredients: Optional[str] = None
    raw_extracted: Optional[str] = None
    confidence: Optional[str] = "unknown"
    image_filename: Optional[str] = "imported.jpg"
    image_path: Optional[str] = ""


class ImportPayload(BaseModel):
    records: list[ImportRecord]
    skip_possible_duplicates: bool = True
    note: Optional[str] = "Imported from backup"


class RestoreBundlePayload(BaseModel):
    archive_base64: str
    archive_filename: Optional[str] = "mediscan-bundle.zip"
