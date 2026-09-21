from httpx import AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.db.models import DeviceStatus, EnrolledDevice, Organization, Site
from tests.helpers import DEFAULT_ORGANIZATION_CODE, DEFAULT_SITE_CODE, create_organization_and_site, enroll


async def test_new_device_enrolls_successfully(client: AsyncClient, db_session: AsyncSession):
    await create_organization_and_site(db_session)

    response = await enroll(client, device_id="PDT-1")

    assert response.status_code == 201
    body = response.json()
    assert body["device_id"] == "PDT-1"
    assert body["organization_code"] == DEFAULT_ORGANIZATION_CODE
    assert body["site_code"] == DEFAULT_SITE_CODE
    assert body["status"] == "ACTIVE"
    assert body["enrollment_credential"]


async def test_enrollment_with_unknown_organization_is_rejected(
    client: AsyncClient, db_session: AsyncSession
):
    await create_organization_and_site(db_session)

    response = await enroll(client, device_id="PDT-1b", organization_code="NO-SUCH-ORG")

    assert response.status_code == 404
    assert response.json()["error_code"] == "organization_not_found"


async def test_enrollment_with_unknown_site_is_rejected(client: AsyncClient, db_session: AsyncSession):
    await create_organization_and_site(db_session)

    response = await enroll(client, device_id="PDT-1c", site_code="NO-SUCH-SITE")

    assert response.status_code == 404
    assert response.json()["error_code"] == "site_not_found"


async def test_enrollment_with_unknown_organization_does_not_create_organization(
    client: AsyncClient, db_session: AsyncSession
):
    """Encodes the invariant that Android enrollment can never create an
    organization - enrollment_service only ever resolves/rejects, it never
    inserts (see CLAUDE.md and services/provisioning_service.py). A 404
    strongly implies this, but this test checks the database directly rather
    than relying on that inference.
    """
    await create_organization_and_site(db_session)

    response = await enroll(client, device_id="PDT-1e", organization_code="NO-SUCH-ORG")
    assert response.status_code == 404

    result = await db_session.execute(
        select(Organization).where(Organization.organization_code == "NO-SUCH-ORG")
    )
    assert result.scalar_one_or_none() is None


async def test_enrollment_with_unknown_site_does_not_create_site(
    client: AsyncClient, db_session: AsyncSession
):
    """Same invariant as above, for sites: a rejected enrollment must never
    create a Site row under the resolved organization.
    """
    await create_organization_and_site(db_session)

    response = await enroll(client, device_id="PDT-1f", site_code="NO-SUCH-SITE")
    assert response.status_code == 404

    result = await db_session.execute(select(Site).where(Site.site_code == "NO-SUCH-SITE"))
    assert result.scalar_one_or_none() is None


async def test_enrollment_with_site_from_different_organization_is_rejected(
    client: AsyncClient, db_session: AsyncSession
):
    await create_organization_and_site(
        db_session, organization_code="TEST-ORG-001", site_code="SHARED-SITE"
    )
    await create_organization_and_site(
        db_session, organization_code="TEST-ORG-002", site_code="OTHER-SITE-002"
    )

    response = await enroll(
        client,
        device_id="PDT-1d",
        organization_code="TEST-ORG-002",
        site_code="SHARED-SITE",
    )

    assert response.status_code == 404
    assert response.json()["error_code"] == "site_organization_mismatch"


async def test_duplicate_device_id_is_rejected(client: AsyncClient, db_session: AsyncSession):
    await create_organization_and_site(db_session)

    first = await enroll(client, device_id="PDT-2")
    assert first.status_code == 201

    second = await enroll(client, device_id="PDT-2")

    assert second.status_code == 409


async def test_reenrollment_of_revoked_device_id_is_rejected(
    client: AsyncClient, db_session: AsyncSession
):
    await create_organization_and_site(db_session)

    first = await enroll(client, device_id="PDT-2b")
    assert first.status_code == 201

    revoke_response = await client.delete("/enrolled_devices/PDT-2b")
    assert revoke_response.status_code == 200

    db_session.expire_all()
    result = await db_session.execute(
        select(EnrolledDevice).where(EnrolledDevice.device_id == "PDT-2b")
    )
    before = result.scalar_one()
    assert before.status == DeviceStatus.REVOKED
    original_site_id = before.site_id
    original_credential_hash = before.credential_hash

    second = await enroll(client, device_id="PDT-2b")

    assert second.status_code == 409
    assert second.json()["error_code"] == "device_already_enrolled"

    db_session.expire_all()
    result = await db_session.execute(
        select(EnrolledDevice).where(EnrolledDevice.device_id == "PDT-2b")
    )
    after = result.scalar_one()
    assert after.status == DeviceStatus.REVOKED
    assert after.site_id == original_site_id
    assert after.credential_hash == original_credential_hash


async def test_invalid_request_is_rejected(client: AsyncClient, db_session: AsyncSession):
    await create_organization_and_site(db_session)

    response = await client.post(
        "/enrollment",
        json={
            "organization_code": DEFAULT_ORGANIZATION_CODE,
            "site_code": DEFAULT_SITE_CODE,
            "device_id": "",
        },
    )

    assert response.status_code == 422


async def test_missing_fields_are_rejected(client: AsyncClient):
    response = await client.post("/enrollment", json={"device_id": "PDT-3"})

    assert response.status_code == 422


async def test_plaintext_credential_is_never_stored(client: AsyncClient, db_session: AsyncSession):
    await create_organization_and_site(db_session)

    response = await enroll(client, device_id="PDT-4")
    credential = response.json()["enrollment_credential"]

    result = await db_session.execute(
        select(EnrolledDevice).where(EnrolledDevice.device_id == "PDT-4")
    )
    device = result.scalar_one()

    assert device.credential_hash != credential
    assert credential not in device.credential_hash


async def test_credential_is_stored_as_argon2id_hash(client: AsyncClient, db_session: AsyncSession):
    await create_organization_and_site(db_session)

    await enroll(client, device_id="PDT-5")

    result = await db_session.execute(
        select(EnrolledDevice).where(EnrolledDevice.device_id == "PDT-5")
    )
    device = result.scalar_one()

    assert device.credential_hash.startswith("$argon2id$")


async def test_enrolled_device_is_active(client: AsyncClient, db_session: AsyncSession):
    await create_organization_and_site(db_session)

    await enroll(client, device_id="PDT-6")

    result = await db_session.execute(
        select(EnrolledDevice).where(EnrolledDevice.device_id == "PDT-6")
    )
    device = result.scalar_one()

    assert device.status == DeviceStatus.ACTIVE


async def test_enrollment_never_queries_nonexistent_organization_status_column(
    client: AsyncClient, db_session: AsyncSession, engine
):
    """The organizations table has no `status` column. Regression for the
    UndefinedColumnError (HTTP 500) raised when the ORM selected
    organizations.status. Captures every SQL statement emitted while
    enrolling and asserts none references it.
    """
    from sqlalchemy import event

    await create_organization_and_site(db_session, organization_code="TARGETTEST-001")

    statements: list[str] = []

    def capture(conn, cursor, statement, parameters, context, executemany):
        statements.append(statement)

    event.listen(engine.sync_engine, "before_cursor_execute", capture)
    try:
        response = await enroll(client, device_id="PDT-TT", organization_code="TARGETTEST-001")
    finally:
        event.remove(engine.sync_engine, "before_cursor_execute", capture)

    assert response.status_code == 201
    assert response.json()["organization_code"] == "TARGETTEST-001"
    org_selects = [s for s in statements if "FROM organizations" in s]
    assert org_selects, "expected the organization lookup to be captured"
    assert not any("organizations.status" in s for s in statements)


def test_organization_model_columns_match_schema():
    assert {c.name for c in Organization.__table__.columns} == {
        "id",
        "organization_code",
        "name",
        "created_at",
        "updated_at",
    }
