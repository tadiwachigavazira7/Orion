from httpx import AsyncClient, Response

DEFAULT_CREDENTIAL = "correct-horse-battery-staple"


async def enroll(
    client: AsyncClient,
    device_id: str = "PDT-100",
    site_id: str = "STORE-1",
    credential: str = DEFAULT_CREDENTIAL,
) -> Response:
    return await client.post(
        "/enrolled_devices",
        json={"device_id": device_id, "site_id": site_id, "enrollment_credential": credential},
    )
