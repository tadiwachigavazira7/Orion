import asyncio
import threading
from datetime import UTC, datetime, timedelta

import pytest

from httpx import ASGITransport, AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from orion_backend.errors import DeviceRevokedError, InvalidCredentialError
from orion_backend.main import create_app
from orion_backend.services import admin_service, device_service
from orion_backend.services.credential_hasher import Argon2CredentialHasher
from tests.conftest import OFFLINE_AUTHORIZATION_HOURS, make_settings
from tests.helpers import (
    DEFAULT_ORGANIZATION_CODE,
    DEFAULT_SITE_CODE,
    authorize_and_enroll,
    create_organization_and_site,
    fetch_device,
    parse_timestamp,
    verify,
)


async def _enrolled(client: AsyncClient, db_session: AsyncSession, device_id: str) -> str:
    await create_organization_and_site(db_session)
    response = await authorize_and_enroll(client, db_session, device_id)
    assert response.status_code == 201
    return response.json()["enrollment_credential"]


async def test_correct_credential_verifies(client: AsyncClient, db_session: AsyncSession):
    credential = await _enrolled(client, db_session, "PDT-10")

    before = datetime.now(UTC)
    response = await verify(client, "PDT-10", credential)
    after = datetime.now(UTC)

    assert response.status_code == 200
    body = response.json()
    server_time = parse_timestamp(body.pop("server_time"))
    offline_valid_until = parse_timestamp(body.pop("offline_valid_until"))
    assert body == {
        "verified": True,
        "device_id": "PDT-10",
        "organization_code": DEFAULT_ORGANIZATION_CODE,
        "site_code": DEFAULT_SITE_CODE,
        "status": "ACTIVE",
    }
    # Server-computed: the server's own clock, plus the configured window.
    assert before <= server_time <= after
    assert offline_valid_until - server_time == timedelta(hours=OFFLINE_AUTHORIZATION_HOURS)


async def test_offline_window_follows_configuration(
    db_session: AsyncSession, sessionmaker_: async_sessionmaker[AsyncSession]
):
    app = create_app(make_settings(offline_authorization_hours=3))
    app.state.sessionmaker = sessionmaker_
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as c:
        credential = await _enrolled(c, db_session, "PDT-10b")
        body = (await verify(c, "PDT-10b", credential)).json()

    window = parse_timestamp(body["offline_valid_until"]) - parse_timestamp(body["server_time"])
    assert window == timedelta(hours=3)


async def test_incorrect_credential_is_denied(client: AsyncClient, db_session: AsyncSession):
    await _enrolled(client, db_session, "PDT-11")

    response = await verify(client, "PDT-11", "wrong-credential")

    assert response.status_code == 401
    assert response.json()["error_code"] == "invalid_credential"
    assert "offline_valid_until" not in response.json()


async def test_unknown_device_verification_fails(client: AsyncClient):
    response = await verify(client, "PDT-does-not-exist", "irrelevant-credential")

    assert response.status_code == 404
    assert response.json()["error_code"] == "device_not_found"


async def test_revoked_device_with_correct_credential_is_told_revoked(
    client: AsyncClient, db_session: AsyncSession
):
    credential = await _enrolled(client, db_session, "PDT-12")
    await admin_service.revoke_device(db_session, "PDT-12")

    response = await verify(client, "PDT-12", credential)

    assert response.status_code == 403
    assert response.json()["error_code"] == "device_revoked"
    assert response.json().get("verified") is not True


async def test_credential_is_checked_before_revoked_status_is_revealed(
    client: AsyncClient, db_session: AsyncSession
):
    await _enrolled(client, db_session, "PDT-12b")
    await admin_service.revoke_device(db_session, "PDT-12b")

    response = await verify(client, "PDT-12b", "wrong-credential")

    # Without the real credential the caller learns nothing about status.
    assert response.status_code == 401
    assert response.json()["error_code"] == "invalid_credential"
    assert "REVOKED" not in response.text and "revoked" not in response.text


async def test_successful_verification_updates_last_verified_at(
    client: AsyncClient, db_session: AsyncSession
):
    credential = await _enrolled(client, db_session, "PDT-13")
    assert (await fetch_device(db_session, "PDT-13")).last_verified_at is None

    assert (await verify(client, "PDT-13", credential)).status_code == 200

    assert (await fetch_device(db_session, "PDT-13")).last_verified_at is not None


async def test_failed_verification_does_not_update_last_verified_at(
    client: AsyncClient, db_session: AsyncSession
):
    await _enrolled(client, db_session, "PDT-14")

    assert (await verify(client, "PDT-14", "wrong-credential")).status_code == 401

    assert (await fetch_device(db_session, "PDT-14")).last_verified_at is None


async def test_revoked_verification_does_not_update_last_verified_at(
    client: AsyncClient, db_session: AsyncSession
):
    credential = await _enrolled(client, db_session, "PDT-15")
    await admin_service.revoke_device(db_session, "PDT-15")

    assert (await verify(client, "PDT-15", credential)).status_code == 403

    assert (await fetch_device(db_session, "PDT-15")).last_verified_at is None


class GatedHasher(Argon2CredentialHasher):
    """Pauses inside verify() (which runs in a worker thread) until released,
    so a test can commit an operator change between the credential check
    and the success update."""

    def __init__(self) -> None:
        super().__init__()
        self.entered = threading.Event()
        self.release = threading.Event()

    def verify(self, credential: str, credential_hash: str) -> bool:
        result = super().verify(credential, credential_hash)
        self.entered.set()
        assert self.release.wait(10)
        return result


async def _verify_racing(
    sessionmaker_: async_sessionmaker[AsyncSession],
    db_session: AsyncSession,
    device_id: str,
    credential: str,
    operator_action,
):
    hasher = GatedHasher()

    async def _verify():
        async with sessionmaker_() as session:
            return await device_service.verify_device(
                session, hasher, device_id, credential, offline_authorization_hours=24
            )

    task = asyncio.create_task(_verify())
    try:
        assert await asyncio.to_thread(hasher.entered.wait, 10)
        # Must not block: verification holds no row lock while hashing.
        await asyncio.wait_for(operator_action(db_session, device_id), timeout=5)
    finally:
        hasher.release.set()
    return task


@pytest.mark.parametrize(
    ("operator_action", "expected_error"),
    [
        (admin_service.reset_device, InvalidCredentialError),
        (admin_service.revoke_device, DeviceRevokedError),
    ],
)
async def test_operator_change_during_verification_never_returns_success(
    operator_action,
    expected_error,
    client: AsyncClient,
    db_session: AsyncSession,
    sessionmaker_: async_sessionmaker[AsyncSession],
):
    credential = await _enrolled(client, db_session, "PDT-16")

    task = await _verify_racing(sessionmaker_, db_session, "PDT-16", credential, operator_action)

    with pytest.raises(expected_error):
        await asyncio.wait_for(task, timeout=10)
    assert (await fetch_device(db_session, "PDT-16")).last_verified_at is None


async def test_slow_verification_does_not_block_operator_revoke(
    client: AsyncClient,
    db_session: AsyncSession,
    sessionmaker_: async_sessionmaker[AsyncSession],
):
    """An unauthenticated caller with a wrong credential must not be able to
    hold the device row while Argon2id runs."""
    await _enrolled(client, db_session, "PDT-17")

    task = await _verify_racing(
        sessionmaker_, db_session, "PDT-17", "wrong-credential", admin_service.revoke_device
    )

    with pytest.raises(InvalidCredentialError):
        await asyncio.wait_for(task, timeout=10)
