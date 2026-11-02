"""Operator transitions (revoke/reset) and their effect on the public API."""

import pytest
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.db.models import DeviceStatus
from orion_backend.errors import DeviceNotFoundError, InvalidDeviceTransitionError
from orion_backend.services import admin_service
from tests.helpers import (
    authorize_and_enroll,
    create_organization_and_site,
    enroll,
    fetch_authorization,
    fetch_device,
    issue_authorization,
    verify,
)


async def _enrolled(client: AsyncClient, db_session: AsyncSession, device_id: str) -> str:
    await create_organization_and_site(db_session)
    response = await authorize_and_enroll(client, db_session, device_id)
    assert response.status_code == 201
    return response.json()["enrollment_credential"]


async def test_revoke_moves_active_device_to_revoked_and_keeps_credential_hash(
    client: AsyncClient, db_session: AsyncSession
):
    await _enrolled(client, db_session, "PDT-20")
    original_hash = (await fetch_device(db_session, "PDT-20")).credential_hash

    result = await admin_service.revoke_device(db_session, "PDT-20")

    assert result.previous_status == DeviceStatus.ACTIVE
    assert result.status == DeviceStatus.REVOKED
    assert result.changed
    device = await fetch_device(db_session, "PDT-20")
    assert device.status == DeviceStatus.REVOKED
    assert device.credential_hash == original_hash


async def test_revoke_is_idempotent_for_revoked_device(client: AsyncClient, db_session: AsyncSession):
    await _enrolled(client, db_session, "PDT-21")
    await admin_service.revoke_device(db_session, "PDT-21")

    result = await admin_service.revoke_device(db_session, "PDT-21")

    assert not result.changed
    assert result.status == DeviceStatus.REVOKED


async def test_revoke_of_pending_device_is_rejected(client: AsyncClient, db_session: AsyncSession):
    await _enrolled(client, db_session, "PDT-22")
    await admin_service.reset_device(db_session, "PDT-22")

    with pytest.raises(InvalidDeviceTransitionError):
        await admin_service.revoke_device(db_session, "PDT-22")
    assert (await fetch_device(db_session, "PDT-22")).status == DeviceStatus.PENDING_ENROLLMENT


async def test_revoke_and_reset_of_unknown_device_are_rejected(db_session: AsyncSession):
    with pytest.raises(DeviceNotFoundError):
        await admin_service.revoke_device(db_session, "PDT-nope")
    with pytest.raises(DeviceNotFoundError):
        await admin_service.reset_device(db_session, "PDT-nope")


async def test_revoked_device_stays_blocked_until_reset(client: AsyncClient, db_session: AsyncSession):
    credential = await _enrolled(client, db_session, "PDT-23")
    await admin_service.revoke_device(db_session, "PDT-23")

    # Neither verification nor enrollment brings it back.
    assert (await verify(client, "PDT-23", credential)).status_code == 403
    response = await enroll(client, "PDT-23", "anything")
    assert response.status_code == 409
    assert response.json()["error_code"] == "device_already_enrolled"
    assert (await fetch_device(db_session, "PDT-23")).status == DeviceStatus.REVOKED


async def test_reset_clears_credential_and_old_credential_fails(
    client: AsyncClient, db_session: AsyncSession
):
    credential = await _enrolled(client, db_session, "PDT-24")
    site_id = (await fetch_device(db_session, "PDT-24")).site_id

    result = await admin_service.reset_device(db_session, "PDT-24")

    assert result.status == DeviceStatus.PENDING_ENROLLMENT
    device = await fetch_device(db_session, "PDT-24")
    assert device.status == DeviceStatus.PENDING_ENROLLMENT
    assert device.credential_hash is None
    assert device.site_id == site_id  # binding kept
    response = await verify(client, "PDT-24", credential)
    assert response.status_code == 401
    assert response.json()["error_code"] == "invalid_credential"


async def test_reset_from_revoked_then_controlled_reenrollment_succeeds(
    client: AsyncClient, db_session: AsyncSession
):
    old_credential = await _enrolled(client, db_session, "PDT-25")
    await admin_service.revoke_device(db_session, "PDT-25")
    await admin_service.reset_device(db_session, "PDT-25")

    # Reset alone does not let the device enroll.
    assert (await enroll(client, "PDT-25", "anything")).status_code == 403

    secret = await issue_authorization(db_session, "PDT-25")
    response = await enroll(client, "PDT-25", secret)

    assert response.status_code == 201
    new_credential = response.json()["enrollment_credential"]
    assert new_credential != old_credential
    assert (await verify(client, "PDT-25", new_credential)).status_code == 200
    assert (await verify(client, "PDT-25", old_credential)).status_code == 401
    assert (await fetch_device(db_session, "PDT-25")).status == DeviceStatus.ACTIVE


async def test_reset_and_revoke_invalidate_outstanding_authorizations(
    client: AsyncClient, db_session: AsyncSession
):
    await _enrolled(client, db_session, "PDT-26")
    await admin_service.reset_device(db_session, "PDT-26")
    secret = await issue_authorization(db_session, "PDT-26")

    # Resetting again (no-op on status) voids the outstanding authorization.
    result = await admin_service.reset_device(db_session, "PDT-26")

    assert not result.changed
    assert result.invalidated_authorizations == 1
    assert (await fetch_authorization(db_session, secret)).invalidated_at is not None
    assert (await enroll(client, "PDT-26", secret)).status_code == 403


async def test_post_enrollment_never_reactivates_or_overwrites_active_device(
    client: AsyncClient, db_session: AsyncSession
):
    credential = await _enrolled(client, db_session, "PDT-27")
    before = await fetch_device(db_session, "PDT-27")

    response = await enroll(client, "PDT-27", "anything")

    assert response.status_code == 409
    after = await fetch_device(db_session, "PDT-27")
    assert after.credential_hash == before.credential_hash
    assert (await verify(client, "PDT-27", credential)).status_code == 200
