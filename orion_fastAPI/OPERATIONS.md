# Orion enrollment backend: operations guide

This guide is for operators. It covers the operator CLI, device lifecycle rules, and how to recover a device.

All management happens through the operator CLI, which talks directly to the enrollment database using `DATABASE_URL`. **There is no admin HTTP API.** The only public routes are `POST /enrollment` and `POST /enrolled_devices/{device_id}/verify`. The old public `GET` and `DELETE /enrolled_devices/{device_id}` routes have been removed.

Run every command from `orion_fastAPI/`:

```bash
uv run python -m orion_backend.cli <command> [options]
uv run python -m orion_backend.cli --help
uv run python -m orion_backend.cli <command> --help
```

Exit codes:

| Code | Meaning |
|---|---|
| 0 | Success, including an explicit no-op such as "already exists" or "already REVOKED" |
| 1 | Rejected by a business rule (conflict, not found, invalid input). Prints `error [<error_code>]: <message>` to stderr |
| 2 | Usage error (missing or unknown arguments) |
| 3 | Configuration, database, or unexpected error. Prints only the error type, never a stack trace or SQL parameters |

Output is sanitized. Device credentials, credential hashes, and authorization hashes are never printed. The only secret the CLI ever prints is a newly issued enrollment authorization, shown once on stdout.

---

## Device lifecycle

```text
(no record)        + valid enrollment authorization  -> ACTIVE              (POST /enrollment)
ACTIVE                                               -> REVOKED             (revoke-device)
ACTIVE | REVOKED                                     -> PENDING_ENROLLMENT  (reset-device)
PENDING_ENROLLMENT + valid enrollment authorization  -> ACTIVE              (POST /enrollment)
```

| State | Credential | Verify with the right credential | POST /enrollment |
|---|---|---|---|
| ACTIVE | Argon2id hash | 200, with an offline window | 409 `device_already_enrolled` |
| REVOKED | hash kept | 403 `device_revoked` | 409 `device_already_enrolled` |
| PENDING_ENROLLMENT | none (NULL) | 401 `invalid_credential` (no credential can match) | 201 with a valid authorization for this device at its own site |

`POST /enrollment` never reactivates or overwrites an ACTIVE or REVOKED device. A REVOKED device stays blocked until an operator resets it and then issues a new authorization.

Verification checks the credential before it reveals the device status. A caller without the real credential only ever gets `device_not_found` or `invalid_credential`.

The server decides the offline window. Every successful enroll or verify returns `server_time` and `offline_valid_until = server_time + OFFLINE_AUTHORIZATION_HOURS` (default 24).

## Enrollment authorizations

- Each authorization is issued only by `issue-enrollment-authorization` and is scoped to one organization, one site, and one intended `device_id`.
- It is short-lived: `ENROLLMENT_AUTHORIZATION_TTL_MINUTES`, default 30. That default is an implementation choice, not an approved business requirement.
- It is single-use. It is consumed in the same database transaction that creates or reactivates the device. If enrollment fails for any reason (bad site code, server error), the transaction rolls back and the authorization stays usable until it expires.
- Two concurrent enrollments with the same authorization produce exactly one success and one credential. The other request gets 403 or 409.
- Issuing a new authorization for a device voids the previous outstanding one, so at most one is outstanding per device. `revoke-device` and `reset-device` also void outstanding authorizations.
- Every reason an authorization can be rejected (unknown, expired, used, voided, wrong device, wrong site or organization) returns the same response: 403 `invalid_enrollment_authorization`.
- Storage: only a SHA-256 digest of the secret is stored. The secret comes from `secrets.token_urlsafe(32)` (256 bits of randomness), so it cannot be brute-forced, and a deterministic digest allows an indexed lookup and an atomic conditional `UPDATE`. Device credentials are different: they are stored as Argon2id hashes.

Hand the authorization to the person enrolling the device over a channel appropriate for a short-lived, single-use secret. Do not paste it into tickets or chat logs that are kept long-term.

---

## Command reference

### create-organization

```bash
uv run python -m orion_backend.cli create-organization --organization-code ACME --name "Acme Retail"
```

- New code: creates the organization.
- Same code and same name: no-op, exit 0 ("already exists (no change)").
- Same code with a different name: rejected (`organization_conflict`). The CLI never renames an organization.

### create-site

```bash
uv run python -m orion_backend.cli create-site --organization-code ACME --site-code STORE-042
```

- The organization must already exist (`organization_not_found` if not).
- If the site code already exists in that organization, the command is rejected (`site_already_exists`). The same site code may exist in a different organization.

### issue-enrollment-authorization

```bash
uv run python -m orion_backend.cli issue-enrollment-authorization \
    --organization-code ACME --site-code STORE-042 --device-id PDT-0001
```

Prints the organization, site, device, `expires_at` (UTC), and the `enrollment_authorization` value. The value is shown once and cannot be retrieved later. If it is lost, issue a new one, which voids the old one.

| Device state | Result |
|---|---|
| No record yet | Issued (first enrollment) |
| PENDING_ENROLLMENT at this site | Issued |
| PENDING_ENROLLMENT at another site or organization | Rejected, `device_site_conflict`. Devices are never moved |
| ACTIVE or REVOKED | Rejected, `device_reset_required`. Run `reset-device` first |
| Unknown organization or site, or site in another org | `organization_not_found` / `site_not_found` / `site_organization_mismatch` |

The command is not idempotent: every successful run creates a new secret and voids the previous one.

### inspect-device

```bash
uv run python -m orion_backend.cli inspect-device --device-id PDT-0001
```

Prints `device_id`, organization, site, `status`, `has_credential` (yes/no), `created_at`, `updated_at`, `last_verified_at`, and the number and latest expiry of currently usable enrollment authorizations. It never prints a credential or any hash. An unknown device returns `device_not_found`.

### revoke-device

```bash
uv run python -m orion_backend.cli revoke-device --device-id PDT-0001
```

| Device state | Result |
|---|---|
| ACTIVE | Becomes REVOKED. The credential hash is kept so the device is told "revoked". Outstanding authorizations are voided |
| REVOKED | No-op, exit 0 |
| PENDING_ENROLLMENT | Rejected, `invalid_device_transition`. There is no credential to revoke. To cancel a pending re-enrollment, run `reset-device` again, which voids outstanding authorizations |
| Unknown | `device_not_found` |

### reset-device

```bash
uv run python -m orion_backend.cli reset-device --device-id PDT-0001
```

| Device state | Result |
|---|---|
| ACTIVE or REVOKED | Becomes PENDING_ENROLLMENT. The credential hash is cleared, so the old credential stops verifying immediately. The record and its site binding are kept. Outstanding authorizations are voided. **No new authorization is issued** |
| PENDING_ENROLLMENT | No status change, exit 0. Any outstanding authorization is voided, which makes this the way to cancel a pending re-enrollment |
| Unknown | `device_not_found` |

---

## Recovery procedures

The fix is always the same: **reset the device, then issue a new authorization, then re-enroll.** No API call can recover a device by itself, by design.

### Lost credential

Examples: app data cleared, device reimaged, or the credential was deleted.

1. `inspect-device --device-id <id>` to confirm the device and its site.
2. `reset-device --device-id <id>`. The old credential is now dead, even if a copy still exists somewhere.
3. `issue-enrollment-authorization --organization-code <org> --site-code <its site> --device-id <id>`.
4. Enter the organization, site, and authorization on the device to enroll it. It becomes ACTIVE with a new credential.

### Lost enrollment response

The server enrolled the device, but the response never reached it (network drop, app killed). The server shows ACTIVE and the device has no credential. Retrying enrollment returns 409 `device_already_enrolled`, which the app reports as "ask an administrator to reset this device". The used authorization cannot be replayed.

Follow the lost-credential steps: reset, issue a new authorization, re-enroll. `inspect-device` showing ACTIVE with `last_verified_at: never` is the typical sign of this case.

### Local save failure

The device received a credential but failed to store it. This is the same situation as a lost credential. The server-side record is ACTIVE and only the reset-and-re-enroll path recovers it. Never try to recover the old credential.

### Compromised or stolen device

1. `revoke-device --device-id <id>`. If the device presents its credential, it is told it is revoked. It stays blocked: enrollment returns 409, and verification returns 403 with the right credential or 401 with a wrong one.
2. Only if the same physical device is later trusted again: `reset-device`, then `issue-enrollment-authorization`, then re-enroll.

### Leaked or mistyped authorization

Issue a new authorization (this voids the old one), or run `reset-device` on a PENDING_ENROLLMENT device to void outstanding authorizations without issuing a new one. For a device that has never enrolled (no record), issuing a new authorization is the only way to void an outstanding one. Otherwise it expires after `ENROLLMENT_AUTHORIZATION_TTL_MINUTES`.

### Device enrolled at the wrong site

Devices are never moved between sites or organizations by any command. Moving one is a deliberate data change outside this CLI; it requires a product decision first.

---

## Development data

`scripts/seed_dev_data.py` creates `TEST-ORG-001` / `TEST-SITE-001` and nothing else: no devices, no authorizations, no secrets. It refuses to run (exit 2) unless `ORION_ENVIRONMENT=development` is explicitly set. Set it per command:

```bash
ORION_ENVIRONMENT=development uv run python scripts/seed_dev_data.py
```

`.env.example` deliberately leaves `ORION_ENVIRONMENT` unset, so copying it never opts you in. Never run the seed script against production.
