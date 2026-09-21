import asyncio

from httpx import AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from orion_backend.db.models import Organization, Site
from orion_backend.errors import OrganizationCodeAlreadyExistsError, SiteCodeAlreadyExistsError
from orion_backend.services import provisioning_service
from tests.helpers import provision_organization, provision_site


async def test_create_organization_succeeds(client: AsyncClient):
    response = await provision_organization(client, organization_code="ORG-A", name="Org A")

    assert response.status_code == 201
    body = response.json()
    assert body["organization_code"] == "ORG-A"
    assert body["name"] == "Org A"
    assert "status" not in body
    assert body["id"]
    assert body["created_at"]
    assert body["updated_at"]


async def test_duplicate_organization_code_is_rejected(client: AsyncClient):
    first = await provision_organization(client, organization_code="ORG-B", name="Org B")
    assert first.status_code == 201

    second = await provision_organization(client, organization_code="ORG-B", name="Org B Again")

    assert second.status_code == 409
    assert second.json()["error_code"] == "organization_code_already_exists"


async def test_create_site_under_existing_organization_succeeds(client: AsyncClient):
    org_response = await provision_organization(client, organization_code="ORG-C", name="Org C")
    assert org_response.status_code == 201

    response = await provision_site(
        client, organization_code="ORG-C", site_code="SITE-C1"
    )

    assert response.status_code == 201
    body = response.json()
    assert body["organization_code"] == "ORG-C"
    assert body["site_code"] == "SITE-C1"
    assert "name" not in body
    assert "status" not in body


async def test_duplicate_site_code_within_same_organization_is_rejected(client: AsyncClient):
    await provision_organization(client, organization_code="ORG-D", name="Org D")

    first = await provision_site(client, organization_code="ORG-D", site_code="SITE-D1")
    assert first.status_code == 201

    second = await provision_site(
        client, organization_code="ORG-D", site_code="SITE-D1"
    )

    assert second.status_code == 409
    assert second.json()["error_code"] == "site_code_already_exists"


async def test_same_site_code_allowed_under_different_organizations(client: AsyncClient):
    await provision_organization(client, organization_code="ORG-E1", name="Org E1")
    await provision_organization(client, organization_code="ORG-E2", name="Org E2")

    first = await provision_site(
        client, organization_code="ORG-E1", site_code="SHARED-SITE"
    )
    second = await provision_site(
        client, organization_code="ORG-E2", site_code="SHARED-SITE"
    )

    assert first.status_code == 201
    assert second.status_code == 201


async def test_create_site_under_nonexistent_organization_is_rejected(client: AsyncClient):
    response = await provision_site(
        client, organization_code="NO-SUCH-ORG", site_code="SITE-X1"
    )

    assert response.status_code == 404
    assert response.json()["error_code"] == "organization_not_found"


async def test_create_organization_with_missing_api_key_is_rejected(
    client: AsyncClient, db_session: AsyncSession
):
    response = await provision_organization(
        client, organization_code="ORG-F", name="Org F", api_key=None
    )

    assert response.status_code == 401

    result = await db_session.execute(
        select(Organization).where(Organization.organization_code == "ORG-F")
    )
    assert result.scalar_one_or_none() is None


async def test_create_organization_with_wrong_api_key_is_rejected(
    client: AsyncClient, db_session: AsyncSession
):
    response = await provision_organization(
        client, organization_code="ORG-G", name="Org G", api_key="wrong-key"
    )

    assert response.status_code == 401

    result = await db_session.execute(
        select(Organization).where(Organization.organization_code == "ORG-G")
    )
    assert result.scalar_one_or_none() is None


async def test_concurrent_create_organization_race_is_translated_to_409(
    sessionmaker_: async_sessionmaker[AsyncSession],
):
    """Two requests racing to create the same organization_code both pass
    the pre-check (neither sees the other's uncommitted insert yet); the
    loser's commit hits the organization_code unique index. That must
    surface as OrganizationCodeAlreadyExistsError (409), not a bare
    IntegrityError propagating as an unhandled 500 - see
    provisioning_service.create_organization's try/except around commit().
    """

    async def attempt() -> Organization:
        async with sessionmaker_() as session:
            return await provisioning_service.create_organization(session, "ORG-RACE", "Org Race")

    results = await asyncio.gather(attempt(), attempt(), return_exceptions=True)

    successes = [r for r in results if isinstance(r, Organization)]
    failures = [r for r in results if isinstance(r, BaseException)]

    assert len(successes) == 1
    assert len(failures) == 1
    assert isinstance(failures[0], OrganizationCodeAlreadyExistsError)


async def test_concurrent_create_site_race_is_translated_to_409(
    sessionmaker_: async_sessionmaker[AsyncSession],
):
    """Same race as above, one level down: two requests racing to create the
    same site_code under the same organization. The loser's commit hits
    uq_sites_organization_id_site_code and must surface as
    SiteCodeAlreadyExistsError (409), not a bare 500.
    """
    async with sessionmaker_() as setup_session:
        await provisioning_service.create_organization(setup_session, "ORG-SITE-RACE", "Org")

    async def attempt() -> tuple[Site, Organization]:
        async with sessionmaker_() as session:
            return await provisioning_service.create_site(
                session, "ORG-SITE-RACE", "SITE-RACE"
            )

    results = await asyncio.gather(attempt(), attempt(), return_exceptions=True)

    successes = [r for r in results if not isinstance(r, BaseException)]
    failures = [r for r in results if isinstance(r, BaseException)]

    assert len(successes) == 1
    assert len(failures) == 1
    assert isinstance(failures[0], SiteCodeAlreadyExistsError)


async def test_create_site_with_missing_api_key_is_rejected(
    client: AsyncClient, db_session: AsyncSession
):
    await provision_organization(client, organization_code="ORG-H", name="Org H")

    response = await provision_site(
        client, organization_code="ORG-H", site_code="SITE-H1", api_key=None
    )

    assert response.status_code == 401

    result = await db_session.execute(select(Site).where(Site.site_code == "SITE-H1"))
    assert result.scalar_one_or_none() is None
