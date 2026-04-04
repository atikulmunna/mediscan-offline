from typing import Optional

from pydantic import BaseModel


class ExtractRequest(BaseModel):
    ocr_text: str
    mode: Optional[str] = "hybrid"
    fields: list[str] | None = None


class ExtractResponse(BaseModel):
    extracted: dict
    provider: str = "cloud-rules"
    field_meta: dict = {}
    review_hints: list[str] = []
