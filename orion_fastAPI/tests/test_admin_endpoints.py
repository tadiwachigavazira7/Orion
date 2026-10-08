import uuid

import pytest
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession

from tests.helpers import (
    DEFAULT_ORGANIZATION_CODE,
    DEFAULT_SITE_CODE,
    admin_headers,
    create_organization_and_site,
    enroll,
    provision_organization,
    provision_site,
)

_NEW_ADMIN_ROUTES = [
    ("GET", f"/admin/organizations/{DEFAULT_ORGANIZATION_CODE}/sites"),
    ("GET", f"/admin/sites/{uuid.uuid4()}/devices"),
    ("GET", "/admin/devices/PDT-1"),
    ("POST", "/admin/devices/PDT-1/deactivate"),
    ("GET", f"/admin/organizations/{DEFAULT_ORGANIZATION_CODE}/sites/{DEFAULT_SITE_CODE}/provisioning"),
]


@pytest.mark.parametrize(("method", "path"), _NEW_ADMIN_ROUTES)
async def test_new_admin_routes_require_api_key(client: AsyncClient, method: str, path: str):
    missing = await client.request(method, path)
    wrong = await client.request(method, path, headers={"X-Admin-Api-Key": "definitely-wrong-key-1"})

    assert missing.status_code == 401
    assert wrong.status_code == 401


async def test_list_sites_for_organization(client: AsyncClient):
    await provision_organization(client, organization_code="ORG-A")
    await provision_organization(client, organization_code="ORG-B")
    await provision_site(client, organization_code="ORG-A", site_code="S1")
    await provision_site(client, organization_code="ORG-A", site_code="S2")
    await provision_site(client, organization_code="ORG-B", site_code="S3")

    response = await client.get("/admin/organizations/ORG-A/sites", headers=admin_headers())

    assert response.status_code == 200
    body = response.json()
    assert [s["site_code"] for s in body] == ["S1", "S2"]
    assert {s["organization_code"] for s in body} == {"ORG-A"}


async def test_list_sites_unknown_organization_returns_404(client: AsyncClient):
    response = await client.get("/admin/organizations/NOPE/sites", headers=admin_headers())

    assert response.status_code == 404
    assert response.json()["error_code"] == "organization_not_found"


async def test_list_devices_for_site(client: AsyncClient, db_session: AsyncSession):
    _, site = await create_organization_and_site(db_session)
    _, other_site = await create_organization_and_site(db_session, "ORG-2", "SITE-2")
    enroll_a = await enroll(client, device_id="PDT-A")
    await enroll(client, device_id="PDT-B")
    await enroll(client, device_id="PDT-C", organization_code="ORG-2", site_code="SITE-2")

    response = await client.get(f"/admin/sites/{site.id}/devices", headers=admin_headers())

    assert response.status_code == 200
    body = response.json()
    assert sorted(d["device_id"] for d in body) == ["PDT-A", "PDT-B"]
    for device in body:
        assert set(device) == {
            "device_id",
            "site_code",
            "organization_code",
            "status",
            "created_at",
            "updated_at",
            "last_verified_at",
        }
        assert device["site_code"] == DEFAULT_SITE_CODE
        assert device["status"] == "ACTIVE"
    lowered = response.text.lower()
    assert "credential" not in lowered
    assert "hash" not in lowered
    assert enroll_a.json()["enrollment_credential"] not in response.text

    other = await client.get(f"/admin/sites/{other_site.id}/devices", headers=admin_headers())
    assert [d["device_id"] for d in other.json()] == ["PDT-C"]


async def test_list_devices_for_site_with_no_devices_is_empty_list(
    client: AsyncClient, db_session: AsyncSession
):
    _, site = await create_organization_and_site(db_session)

    response = await client.get(f"/admin/sites/{site.id}/devices", headers=admin_headers())

    assert response.status_code == 200
    assert response.json() == []


async def test_list_devices_unknown_site_returns_404(client: AsyncClient):
    response = await client.get(f"/admin/sites/{uuid.uuid4()}/devices", headers=admin_headers())

    assert response.status_code == 404
    assert response.json()["error_code"] == "site_not_found"


async def test_provisioning_info(client: AsyncClient):
    await provision_organization(client, organization_code="ORG-P", name="Provisioned Org")
    site_response = await provision_site(client, organization_code="ORG-P", site_code="SITE-P")

    response = await client.get(
        "/admin/organizations/ORG-P/sites/SITE-P/provisioning", headers=admin_headers()
    )

    assert response.status_code == 200
    assert response.json() == {
        "organization_code": "ORG-P",
        "organization_name": "Provisioned Org",
        "site_code": "SITE-P",
        "site_id": site_response.json()["id"],
    }
    lowered = response.text.lower()
    assert "credential" not in lowered
    assert "hash" not in lowered


async def test_provisioning_info_unknown_organization_or_site_returns_404(client: AsyncClient):
    await provision_organization(client, organization_code="ORG-P")
    await provision_organization(client, organization_code="ORG-Q")
    await provision_site(client, organization_code="ORG-Q", site_code="SITE-Q")

    unknown_org = await client.get(
        "/admin/organizations/NOPE/sites/SITE-Q/provisioning", headers=admin_headers()
    )
    unknown_site = await client.get(
        "/admin/organizations/ORG-P/sites/NOPE/provisioning", headers=admin_headers()
    )
    wrong_org_for_site = await client.get(
        "/admin/organizations/ORG-P/sites/SITE-Q/provisioning", headers=admin_headers()
    )

    assert unknown_org.status_code == 404
    assert unknown_site.status_code == 404
    assert wrong_org_for_site.status_code == 404


async def test_plaintext_credential_only_appears_in_enrollment_response(
    client: AsyncClient, db_session: AsyncSession
):
    _, site = await create_organization_and_site(db_session)
    enroll_response = await enroll(client, device_id="PDT-S")
    credential = enroll_response.json()["enrollment_credential"]
    assert credential

    responses = [
        await client.get("/admin/devices/PDT-S", headers=admin_headers()),
        await client.get(f"/admin/sites/{site.id}/devices", headers=admin_headers()),
        await client.get(
            f"/admin/organizations/{DEFAULT_ORGANIZATION_CODE}/sites", headers=admin_headers()
        ),
        await client.get(
            f"/admin/organizations/{DEFAULT_ORGANIZATION_CODE}/sites/{DEFAULT_SITE_CODE}/provisioning",
            headers=admin_headers(),
        ),
        await client.post("/admin/devices/PDT-S/deactivate", headers=admin_headers()),
    ]
    for response in responses:
        assert response.status_code == 200
        assert credential not in response.text
