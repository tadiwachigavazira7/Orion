from httpx import AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.db.models import EnrolledDevice
from tests.helpers import DEFAULT_ORGANIZATION_CODE, DEFAULT_SITE_CODE, create_organization_and_site, enroll


async def _device(db_session: AsyncSession, device_id: str) -> EnrolledDevice:
    # Re-fetch from the DB rather than trusting db_session's identity map,
    # since the app's request handling commits through a *different* session
    # (see the `client` fixture) - without this, a second call in the same
    # test would just return the stale, already-loaded Python object.
    db_session.expire_all()
    result = await db_session.execute(
        select(EnrolledDevice).where(EnrolledDevice.device_id == device_id)
    )
    return result.scalar_one()


async def test_correct_credential_verifies(client: AsyncClient, db_session: AsyncSession):
    await create_organization_and_site(db_session)
    enroll_response = await enroll(client, device_id="PDT-10")
    credential = enroll_response.json()["enrollment_credential"]

    response = await client.post(
        "/enrolled_devices/PDT-10/verify", json={"enrollment_credential": credential}
    )

    assert response.status_code == 200
    assert response.json() == {
        "verified": True,
        "device_id": "PDT-10",
        "organization_code": DEFAULT_ORGANIZATION_CODE,
        "site_code": DEFAULT_SITE_CODE,
        "status": "ACTIVE",
    }


async def test_incorrect_credential_is_denied(client: AsyncClient, db_session: AsyncSession):
    await create_organization_and_site(db_session)
    await enroll(client, device_id="PDT-11")

    response = await client.post(
        "/enrolled_devices/PDT-11/verify", json={"enrollment_credential": "wrong-credential"}
    )

    assert response.status_code == 401
    assert "verified" not in response.json() or response.json().get("verified") is not True


async def test_unknown_device_verification_fails(client: AsyncClient):
    response = await client.post(
        "/enrolled_devices/PDT-does-not-exist/verify",
        json={"enrollment_credential": "irrelevant-credential"},
    )

    assert response.status_code == 404


async def test_revoked_device_verification_is_denied(client: AsyncClient, db_session: AsyncSession):
    await create_organization_and_site(db_session)
    enroll_response = await enroll(client, device_id="PDT-12")
    credential = enroll_response.json()["enrollment_credential"]

    revoke_response = await client.delete("/enrolled_devices/PDT-12")
    assert revoke_response.status_code == 200

    response = await client.post(
        "/enrolled_devices/PDT-12/verify", json={"enrollment_credential": credential}
    )

    assert response.status_code == 403


async def test_successful_verification_updates_last_verified_at(
    client: AsyncClient, db_session: AsyncSession
):
    await create_organization_and_site(db_session)
    enroll_response = await enroll(client, device_id="PDT-13")
    credential = enroll_response.json()["enrollment_credential"]

    before = await _device(db_session, "PDT-13")
    assert before.last_verified_at is None

    response = await client.post(
        "/enrolled_devices/PDT-13/verify", json={"enrollment_credential": credential}
    )
    assert response.status_code == 200

    after = await _device(db_session, "PDT-13")
    assert after.last_verified_at is not None


async def test_failed_verification_does_not_update_last_verified_at(
    client: AsyncClient, db_session: AsyncSession
):
    await create_organization_and_site(db_session)
    await enroll(client, device_id="PDT-14")

    response = await client.post(
        "/enrolled_devices/PDT-14/verify", json={"enrollment_credential": "wrong-credential"}
    )
    assert response.status_code == 401

    after = await _device(db_session, "PDT-14")
    assert after.last_verified_at is None
