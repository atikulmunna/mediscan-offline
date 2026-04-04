import httpx

from app.core.config import SUPABASE_KEY, SUPABASE_TABLE, SUPABASE_URL


def supa_headers():
    return {
        "apikey": SUPABASE_KEY,
        "Authorization": f"Bearer {SUPABASE_KEY}",
        "Content-Type": "application/json",
        "Prefer": "return=representation,resolution=merge-duplicates",
    }


async def supa_upsert_record(payload: dict):
    url = f"{SUPABASE_URL}/rest/v1/{SUPABASE_TABLE}"
    async with httpx.AsyncClient(timeout=20) as client:
        response = await client.post(
            url,
            headers=supa_headers(),
            params={"on_conflict": "local_id"},
            json=payload,
        )
        response.raise_for_status()


async def supa_delete_record(local_id: int):
    url = f"{SUPABASE_URL}/rest/v1/{SUPABASE_TABLE}"
    async with httpx.AsyncClient(timeout=20) as client:
        response = await client.delete(
            url,
            headers=supa_headers(),
            params={"local_id": f"eq.{local_id}"},
        )
        response.raise_for_status()


async def supa_ping() -> bool:
    url = f"{SUPABASE_URL}/rest/v1/{SUPABASE_TABLE}"
    async with httpx.AsyncClient(timeout=10) as client:
        response = await client.get(
            url,
            headers=supa_headers(),
            params={"select": "local_id", "limit": "1"},
        )
        return response.status_code < 300
