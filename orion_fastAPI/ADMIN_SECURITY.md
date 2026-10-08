# Admin Security Notes

## Admin gate (interim)

All `/admin/*` endpoints (provisioning and device management) are protected by a single static API key sent in the `X-Admin-Api-Key` header and checked against the required `ADMIN_API_KEY` setting (constant-time compare, minimum 16 characters).

This is an interim, single-shared-secret gate suitable for **dev/internal use only**. Generate a key with:

```
python -c "import secrets; print(secrets.token_urlsafe(32))"
```

Before exposing the service publicly in production, the following are needed:

- Real admin identity: per-operator authentication (e.g. OIDC/JWT) with roles, instead of one shared key
- Key rotation (until per-operator auth exists)
- Rate limiting / lockout on failed admin auth
- HTTPS-only transport
- Audit logging of admin actions (who deactivated which device, who created which org/site)
- Network restriction of `/admin` (VPN / IP allow-list / internal ingress only)

## Enrollment is gated only by non-secret codes

`POST /enrollment` is unauthenticated and is gated only by the organization code and site code. These codes are not secrets: anyone who knows both can enroll a device and receive a credential. The only mitigation today is after-the-fact: an admin can see enrolled devices and deactivate them (visibility and deactivation do not prevent enrollment) (`POST /admin/devices/{device_id}/deactivate`).

Recommendation (product decision, **not implemented**): a per-site one-time enrollment token issued by an admin and required at enrollment. This would change the `POST /enrollment` request contract that the Android client (`HttpEnrollmentVerifier`) depends on, so it needs a coordinated app and backend change.

The per-device credential is generated at enrollment, returned exactly once in the enrollment response, and stored only as an Argon2id hash. No admin endpoint returns it or its hash.

## Dev vs prod database

- `DATABASE_URL` is read from the environment only (`.env` locally, which is git-ignored; never committed).
- Dev: local Docker Postgres (see `.env.example`).
- Prod: hosted Postgres, with the URL supplied by the deployment environment's secret store.
- Run `alembic upgrade head` against prod as a deliberate deploy step; the app does not migrate on startup.
- Tests never use the dev/prod database: they use a scratch database (`TEST_DATABASE_URL`, see `tests/conftest.py`), whose schema is created and dropped per test.
