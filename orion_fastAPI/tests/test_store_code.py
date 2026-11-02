"""Store enrollment code generation, normalization, and rotation."""

import re

from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.services import store_code_service
from tests.helpers import create_store, enroll_at, fetch_site, rotate_code, verify

CODE_FORMAT = re.compile(r"^[0-9A-HJKMNP-TV-Z]{5}(-[0-9A-HJKMNP-TV-Z]{5}){3}$")


def test_generated_codes_are_crockford_grouped_and_unique():
    codes = {store_code_service.format_code(store_code_service.generate_code()) for _ in range(200)}

    assert len(codes) == 200
    assert all(CODE_FORMAT.match(code) for code in codes)


def test_normalization_strips_case_separators_and_aliases():
    assert store_code_service.normalize_code(" abcde-fghjk mnpqr-stvwx ") == "ABCDEFGHJKMNPQRSTVWX"
    assert store_code_service.normalize_code("O0IL1") == "00111"
    assert store_code_service.normalize_code(" - ") == ""


async def test_rotation_invalidates_old_code_and_keeps_enrolled_devices(
    client: AsyncClient, db_session: AsyncSession
):
    store = await create_store(db_session)
    first = await enroll_at(client, store, "PDT-before")
    assert first.status_code == 201
    credential = first.json()["enrollment_credential"]
    old_hash = (await fetch_site(db_session, store.site_id)).enrollment_code_hash

    new_code = await rotate_code(db_session, store.organization_code, store.site_code)

    assert new_code != store.code
    site = await fetch_site(db_session, store.site_id)
    assert site.enrollment_code_hash != old_hash
    assert site.enrollment_code_rotated_at is not None
    old = await enroll_at(client, store, "PDT-old-code")
    assert old.status_code == 403
    assert old.json()["error_code"] == "invalid_enrollment_authorization"
    assert (await enroll_at(client, store, "PDT-new-code", code=new_code)).status_code == 201
    # Devices enrolled before the rotation keep verifying.
    assert (await verify(client, "PDT-before", credential)).status_code == 200
