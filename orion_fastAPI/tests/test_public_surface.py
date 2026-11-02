"""The public HTTP surface after enrollment v2: only enrollment and
verification, POST-only CORS, and no unauthenticated management routes."""

import pytest
from httpx import ASGITransport, AsyncClient
from pydantic import ValidationError
from sqlalchemy.ext.asyncio import AsyncSession

from orion_backend.db.models import DeviceStatus
from orion_backend.main import create_app
from tests.conftest import make_settings
from tests.helpers import authorize_and_enroll, create_organization_and_site, fetch_device

ALLOWED_ORIGIN = "https://admin.example-retailer.test"


def test_only_enrollment_and_verification_routes_are_exposed():
    app = create_app(make_settings())
    api_routes = {
        (method.upper(), path)
        for path, operations in app.openapi()["paths"].items()
        for method in operations
    }

    assert api_routes == {
        ("POST", "/enrollment"),
        ("POST", "/enrolled_devices/{device_id}/verify"),
    }


@pytest.mark.parametrize("method", ["GET", "DELETE", "PUT", "PATCH"])
async def test_removed_device_management_routes_are_gone(
    method: str, client: AsyncClient, db_session: AsyncSession
):
    await create_organization_and_site(db_session)
    await authorize_and_enroll(client, db_session, "PDT-50")

    response = await client.request(method, "/enrolled_devices/PDT-50")

    assert response.status_code in (404, 405)
    assert (await fetch_device(db_session, "PDT-50")).status == DeviceStatus.ACTIVE


@pytest.mark.parametrize(
    "path",
    [
        "/enrolled_devices/PDT-51/revoke",
        "/enrolled_devices/PDT-51/reset",
        "/enrollment_authorizations",
        "/enrollment/authorizations",
        "/admin/devices/PDT-51/reset",
        "/organizations",
        "/sites",
    ],
)
async def test_no_public_route_can_revoke_reset_or_issue(
    path: str, client: AsyncClient, db_session: AsyncSession
):
    await create_organization_and_site(db_session)
    await authorize_and_enroll(client, db_session, "PDT-51")

    for method in ("POST", "PUT", "PATCH", "DELETE"):
        response = await client.request(method, path, json={"device_id": "PDT-51"})
        assert response.status_code in (404, 405), (method, path, response.status_code)

    device = await fetch_device(db_session, "PDT-51")
    assert device.status == DeviceStatus.ACTIVE
    assert device.credential_hash is not None


async def _preflight(method: str) -> tuple[int, dict[str, str]]:
    app = create_app(make_settings(cors_origins=ALLOWED_ORIGIN))
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as c:
        response = await c.options(
            "/enrollment",
            headers={
                "Origin": ALLOWED_ORIGIN,
                "Access-Control-Request-Method": method,
                "Access-Control-Request-Headers": "Content-Type",
            },
        )
    return response.status_code, dict(response.headers)


async def test_cors_allows_post_from_configured_origin():
    status, headers = await _preflight("POST")

    assert status == 200
    assert headers["access-control-allow-origin"] == ALLOWED_ORIGIN
    assert [m.strip() for m in headers["access-control-allow-methods"].split(",")] == ["POST"]


@pytest.mark.parametrize("method", ["GET", "DELETE", "PUT", "PATCH", "UPDATE"])
async def test_cors_rejects_other_methods(method: str):
    status, headers = await _preflight(method)

    assert status == 400
    assert "UPDATE" not in headers.get("access-control-allow-methods", "")


async def test_cors_rejects_unconfigured_origin():
    app = create_app(make_settings(cors_origins=ALLOWED_ORIGIN))
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as c:
        response = await c.options(
            "/enrollment",
            headers={"Origin": "https://evil.test", "Access-Control-Request-Method": "POST"},
        )

    assert response.status_code == 400
    assert "access-control-allow-origin" not in response.headers


def test_cors_wildcard_origin_is_refused():
    with pytest.raises(ValidationError):
        make_settings(cors_origins="*")


SECRET_MARKER = "SECRET-MARKER-7f3a"


@pytest.mark.parametrize(
    ("path", "payload", "field"),
    [
        (
            "/enrollment",
            {
                "organization_code": "TEST-ORG-001",
                "site_code": "TEST-SITE-001",
                "device_id": "PDT-52",
                "enrollment_authorization": SECRET_MARKER + "x" * 300,
            },
            "body.enrollment_authorization",
        ),
        (
            "/enrollment",
            {"enrollment_authorization": SECRET_MARKER, "device_id": "PDT-52"},
            "body.organization_code",
        ),
        (
            "/enrolled_devices/PDT-52/verify",
            {"enrollment_credential": SECRET_MARKER + "x" * 5000},
            "body.enrollment_credential",
        ),
        (
            "/enrolled_devices/PDT-52/verify",
            {"enrollment_credential": 12345, "unexpected": SECRET_MARKER},
            "body.enrollment_credential",
        ),
    ],
)
async def test_validation_errors_never_echo_submitted_values(
    path: str, payload: dict, field: str, client: AsyncClient
):
    response = await client.post(path, json=payload)

    assert response.status_code == 422
    assert SECRET_MARKER not in response.text
    assert "12345" not in response.text
    body = response.json()
    assert body["error_code"] == "invalid_request"
    assert body["detail"] == "Invalid request body."
    assert field in body["fields"]


async def test_malformed_json_does_not_echo_body(client: AsyncClient):
    response = await client.post(
        "/enrollment",
        content='{"enrollment_authorization": "' + SECRET_MARKER + '", broken',
        headers={"Content-Type": "application/json"},
    )

    assert response.status_code == 422
    assert SECRET_MARKER not in response.text
    assert response.json()["error_code"] == "invalid_request"
