# Orion

**A hardware-agnostic RFID software layer for real-time item navigation.**

Orion runs directly on top of the RFID/RSSI capabilities of the enterprise PDT scanners retailers already own (Zebra, Impinj, and others). It interprets noisy RFID observations from that existing hardware and guides an employee to a specific tagged item — no proprietary Orion hardware required.

> Orion is the intelligence layer between existing enterprise RFID infrastructure and the employee.

---

## What Orion does

An employee needs to find one specific item on a large floor. They give Orion a target — by scanning, typing, or searching an EPC — and Orion turns the scanner's live RFID readings into simple "hotter / colder" proximity guidance on a compass-style screen until they reach it.

```
Retailer's RFID reader / PDT scanner
        ↓
Vendor SDK  →  Orion vendor adapter  →  RfidObservation (vendor-neutral)
        ↓
Signal processing  →  Localization  →  Navigation state
        ↓
Compass-style employee UI  →  Employee finds the item
```

The core value is the **software intelligence layer**, not hardware. Orion integrates with the retailer's existing inventory and RFID infrastructure rather than replacing it.

---

## Status

This repository is an in-progress **Kotlin/Android rebuild**, currently in the **testing phase** against a local PostgreSQL test database running in Docker.

It contains two parts:

- **`app/`** — the Android/PDT application (Kotlin, Jetpack Compose).
- **`orion_fastAPI/`** — a deliberately narrow backend for **PDT device enrollment, verification, revocation, and reset**. It answers "is this scanner trusted to run Orion, and for which retailer site?" Enrollment requires a short-lived, single-use authorization issued by an operator through a CLI. There is no admin HTTP API. The backend does not hold inventory, EPC, or RFID data; that stays with the retailer's systems and on the device.

> Orion was previously prototyped as a React Native / Expo / Supabase application. That prototype is **deprecated** and has been removed. Do not reintroduce React Native, Expo, TypeScript, or Supabase, and do not grow the backend beyond enrollment without a concrete, documented requirement. See [`CLAUDE.md`](./CLAUDE.md).

---

## Tech stack

- **Language:** Kotlin
- **UI:** Jetpack Compose
- **Async / streaming:** Kotlin Coroutines + Flow
- **Build:** Gradle
- **Target:** Android / enterprise PDT devices

RFID hardware is integrated through a vendor abstraction layer (Zebra, Impinj, …), with each vendor confined to its own adapter module.

**Enrollment backend (`orion_fastAPI/`)**

- **Language:** Python 3.12
- **API:** FastAPI + Uvicorn
- **Database:** PostgreSQL via async SQLAlchemy 2.0 + asyncpg, migrations with Alembic
- **Credentials:** device credentials are stored only as Argon2id hashes. Enrollment authorizations are 256-bit random values stored only as SHA-256 digests. Nothing is stored in plaintext
- **Operations:** an operator CLI (`python -m orion_backend.cli`) with direct database access. There are no admin HTTP routes
- **Tooling:** `uv` for dependencies, pytest for tests

---

## Architecture at a glance

Orion uses a layered architecture. The most important boundary is that **vendor-specific code must never leak into core domain logic**.

```
UI (Jetpack Compose)
  → Application layer (state, use cases)
    → Navigation engine
      → Localization engine
        → Signal processing
          → RFID domain / abstraction
            → Vendor integration layer (Zebra | Impinj | …)
```

Two principles worth knowing before reading the code:

- **EPC is the tag identity everywhere.** Observations, engine, session, and UI all key on `epc`. Orion targets a single specific EPC and does not perform product→tag resolution in core logic.
- **Resolve before navigate.** The RFID interpretation pipeline does not start until a target EPC is resolved. This gate is structural, not advisory.

The full engineering standards, architectural rules, and rationale live in [`CLAUDE.md`](./CLAUDE.md) — read that first.

---

## Project structure

```
app/
└── src/
    ├── main/
    │   ├── java/com/orion/
    │   │   ├── app/                 # Compose screens, ViewModels, MainActivity
    │   │   ├── core/                # vendor-neutral domain logic
    │   │   │   ├── enrollment/      # device enrollment use cases + interfaces
    │   │   │   ├── inventory/       # EPC resolution / validation seam
    │   │   │   ├── navigation/      # navigation engine
    │   │   │   ├── rfid/            # RfidReader boundary, observations
    │   │   │   └── session/         # find-and-navigate use cases
    │   │   ├── data/
    │   │   │   └── enrollment/      # HTTP verifier, DataStore-backed enrollment store
    │   │   └── integrations/        # vendor adapters
    │   │       ├── zebra/
    │   │       ├── impinj/
    │   │       └── fake/            # hardware-free reader + lookup for dev/tests
    │   └── res/                     # drawables, adaptive icons, etc.
    ├── debug/                       # debug-only manifest + network security config
    └── test/                        # JVM unit tests

orion_fastAPI/
├── orion_backend/
│   ├── api/                         # thin FastAPI routers (POST /enrollment, POST .../verify)
│   ├── services/                    # enrollment, authorization, device, admin, credential logic
│   ├── db/                          # SQLAlchemy models and session
│   ├── cli.py                       # operator CLI (orgs, sites, authorizations, revoke/reset)
│   ├── schemas.py, errors.py, config.py
├── alembic/                         # database migrations
├── scripts/seed_dev_data.py         # seeds a test organization + site (development only)
├── OPERATIONS.md                    # operator CLI reference, lifecycle rules, recovery
└── tests/                           # pytest suite (needs the test Postgres)
```

Structure will evolve; folders are created when they earn their place, not to satisfy a theoretical layout.

---

## Getting started

**Prerequisites**
- Android Studio (latest stable), JDK 17+, Android SDK
- An Android emulator or device (a physical PDT scanner is **not** required for core development; see below)
- Python 3.12 and [`uv`](https://docs.astral.sh/uv/)
- Docker Desktop (for the local PostgreSQL)

### 1. Start PostgreSQL (Docker)

The test phase runs against a local Postgres container named `orion-postgres`:

```bash
docker start orion-postgres            # start the existing container
docker ps                              # confirm it's running
docker exec -it orion-postgres psql -U orion   # optional: open a psql shell
```

### 2. Run the enrollment backend

```bash
cd orion_fastAPI
cp .env.example .env                   # then edit DATABASE_URL if needed; never commit .env
uv sync                                # install dependencies
uv run alembic upgrade head            # apply migrations
ORION_ENVIRONMENT=development uv run python scripts/seed_dev_data.py  # seed TEST-ORG-001 / TEST-SITE-001 (dev only)
uv run uvicorn orion_backend.main:app --reload
```

The API listens on `http://localhost:8000`. That is enough for the emulator, which reaches the host through `10.0.2.2`. For a physical device on your LAN, add `--host 0.0.0.0`.

The seed script refuses to run unless `ORION_ENVIRONMENT=development` is explicitly set. Set it on the command, as shown above; `.env.example` deliberately leaves it unset. It seeds only an organization and a site: no devices, no authorizations, no secrets.

Configuration (`.env` / environment, see `.env.example`): `DATABASE_URL`, `ORION_ENVIRONMENT` (`development` | `test` | `production`), `CORS_ORIGINS` (explicit origins only), `OFFLINE_AUTHORIZATION_HOURS` (default 24), `ENROLLMENT_AUTHORIZATION_TTL_MINUTES` (default 30).

### 3. Build and run the Android app

```bash
./gradlew assembleDebug                # build
./gradlew installDebug                 # install to a running emulator/device
```

To start the emulator from the command line:

```powershell
& "C:\Android\Sdk\emulator\emulator.exe" -avd Orion_PDT
& "C:\Android\Sdk\platform-tools\adb.exe" emu kill      # stop it
```

Debug builds talk to the backend at `http://10.0.2.2:8000` (the emulator's alias for the host machine). Cleartext HTTP is allowed **only** for that host, and only in debug. To point a physical device at your machine, override the URL. A LAN address needs HTTPS, or a matching debug-only change to `app/src/debug/res/xml/network_security_config.xml`, because cleartext is blocked for every host except `10.0.2.2`:

```bash
./gradlew installDebug -PORION_ENROLLMENT_BASE_URL=http://<your-lan-ip>:8000
```

Release builds take the backend URL from `-PORION_RELEASE_ENROLLMENT_BASE_URL=https://<host>` (or the `ORION_RELEASE_ENROLLMENT_BASE_URL` environment variable). The build **fails** at `validateReleaseEnrollmentBaseUrl` unless the value is a valid `https` URL. There is no fallback to the emulator URL. No production host has been chosen yet (see *External deployment dependencies*). `UnconfiguredEnrollmentVerifier` remains only as a defensive runtime fallback that never reports success.

### Run the tests

```bash
./gradlew :app:testDebugUnitTest       # Android unit tests (no hardware or backend needed)
                                       # plain `test` also runs release unit tests, which need the release URL

cd orion_fastAPI
uv run pytest                          # backend tests (needs the test Postgres running)
```

Backend tests use `TEST_DATABASE_URL`, which defaults to `postgresql+asyncpg://orion:orion@localhost:55432/orion_test`. Set it if your test database is elsewhere. If Docker isn't running, every backend test fails with a connection error. The tests create and drop their schema in that database on every test, so never point `TEST_DATABASE_URL` at a database whose data you want to keep.

---

## Enrollment backend

### Public API

Handled errors use the shape `{"detail": str, "error_code": str}`. Invalid request bodies return 422 `{"detail": "Invalid request body.", "error_code": "invalid_request", "fields": [...]}`; it lists only the field locations, never the submitted values. Unhandled server errors (500) are plain text (`Internal Server Error`), not JSON. Timestamps are ISO-8601 UTC.

| Route | Purpose | Responses |
|---|---|---|
| `POST /enrollment` | Enroll a device. Body: `organization_code`, `site_code`, `device_id`, `enrollment_authorization` | **201** `device_id`, `organization_code`, `site_code`, `status`, `enrollment_credential` (returned once), `server_time`, `offline_valid_until` · **404** `organization_not_found` / `site_not_found` / `site_organization_mismatch` · **403** `invalid_enrollment_authorization` (one generic code for every authorization failure) · **409** `device_already_enrolled` (device is ACTIVE or REVOKED; never reactivated or overwritten) · **422** `invalid_request` |
| `POST /enrolled_devices/{device_id}/verify` | Check a device credential. Body: `enrollment_credential` | **200** `verified`, `device_id`, `organization_code`, `site_code`, `status`, `server_time`, `offline_valid_until` · **404** `device_not_found` · **401** `invalid_credential` (also returned after a reset, when the device has no credential) · **403** `device_revoked` (only when the credential matches) |

- The credential is checked before the device status is revealed.
- `offline_valid_until = server_time + OFFLINE_AUTHORIZATION_HOURS`. The server decides this window, not the device.
- CORS allows only `POST`, and only from the origins listed in `CORS_ORIGINS`. CORS is not authentication.

**Removed:** `GET /enrolled_devices/{device_id}` and `DELETE /enrolled_devices/{device_id}`. They were unauthenticated, so anyone could read device status or revoke a device. Inspecting, revoking, and resetting devices now happen only through the operator CLI.

### Operator workflow

Operators run the CLI from `orion_fastAPI/` with database access (`DATABASE_URL`):

```bash
uv run python -m orion_backend.cli create-organization --organization-code ACME --name "Acme Retail"
uv run python -m orion_backend.cli create-site --organization-code ACME --site-code STORE-042
uv run python -m orion_backend.cli issue-enrollment-authorization --organization-code ACME --site-code STORE-042 --device-id <device-id>
uv run python -m orion_backend.cli inspect-device --device-id <device-id>
uv run python -m orion_backend.cli revoke-device --device-id <device-id>
uv run python -m orion_backend.cli reset-device --device-id <device-id>
```

Device lifecycle:

- No record + valid authorization → **ACTIVE**
- ACTIVE → **REVOKED** (`revoke-device`)
- ACTIVE or REVOKED → **PENDING_ENROLLMENT** (`reset-device`, which clears the credential)
- PENDING_ENROLLMENT + new authorization → **ACTIVE**

Authorizations are scoped to one organization, one site, and one device. They are single-use and expire after `ENROLLMENT_AUTHORIZATION_TTL_MINUTES`. Issuing a new one voids the previous one. Recovering a device whose credential or enrollment response was lost always follows the same path: reset, issue a new authorization, re-enroll.

See [`orion_fastAPI/OPERATIONS.md`](./orion_fastAPI/OPERATIONS.md) for the full command reference, conflict and idempotency rules, and recovery procedures.

### Local end-to-end enrollment and recovery test

This assumes the backend is running (step 2 above) and the debug app is installed on an emulator. `CLI` is shorthand for `uv run python -m orion_backend.cli`, run from `orion_fastAPI/`.

1. **Seed:** `ORION_ENVIRONMENT=development uv run python scripts/seed_dev_data.py` creates `TEST-ORG-001` / `TEST-SITE-001`.
2. **Find the device ID:** open the app. The enrollment screen shows `Device ID: …`.
3. **Issue an authorization:** `CLI issue-enrollment-authorization --organization-code TEST-ORG-001 --site-code TEST-SITE-001 --device-id <device-id>`. Copy the `enrollment_authorization` value. It is shown only once and expires after 30 minutes by default.
4. **Enroll from the emulator:** enter the organization code, site code, and authorization. Expect success. `CLI inspect-device --device-id <device-id>` should show `status: ACTIVE` and `has_credential: yes`.
5. **Verify:** relaunch the app, or wait for its next check. The server log shows `POST /enrolled_devices/<device-id>/verify` → 200, and `inspect-device` shows a `last_verified_at` timestamp.
6. **Revoke:** `CLI revoke-device --device-id <device-id>`. The app's next verification gets 403 `device_revoked` and the device is blocked. Re-entering an authorization in the app returns 409, and the app asks for an administrator reset.
7. **Reset:** `CLI reset-device --device-id <device-id>`. The status becomes `PENDING_ENROLLMENT` and the old credential now gets 401 `invalid_credential`. Reset does not issue an authorization.
8. **Issue a new authorization:** repeat step 3. The old, consumed authorization is rejected with 403.
9. **Re-enroll:** enter the new authorization in the app. The device becomes ACTIVE with a new credential.

The same flow without the app, using `curl` against `http://localhost:8000`:

```bash
curl -s -X POST localhost:8000/enrollment -H 'Content-Type: application/json' \
  -d '{"organization_code":"TEST-ORG-001","site_code":"TEST-SITE-001","device_id":"PDT-LOCAL-1","enrollment_authorization":"<value>"}'
curl -s -X POST localhost:8000/enrolled_devices/PDT-LOCAL-1/verify -H 'Content-Type: application/json' \
  -d '{"enrollment_credential":"<credential from the enroll response>"}'
```

Don't paste real credentials or authorizations into shared terminals or logs.

### External deployment dependencies (not yet decided)

The backend currently runs only locally. These have **not** been selected:

- A production host and runtime platform
- TLS termination and certificates. Release builds of the app have no backend URL configured and report enrollment as "not configured"
- A managed PostgreSQL instance, with backups and a migration rollout procedure (`alembic upgrade head`)
- Secret management for `DATABASE_URL`
- How operators get audited CLI access to the production database
- Whether operator authorizations should be delivered through an MDM/EMM channel instead of by hand

---

## Developing without RFID hardware

Core logic is intentionally hardware-independent and testable with simulated observations. A `FakeRfidReader` and `FakeEpcLookup` let you exercise the full resolve → navigate flow on a laptop with no scanner attached:

- `FakeRfidReader` emits a scripted stream of `RfidObservation`s (e.g. simulating an employee walking toward a tag).
- `FakeEpcLookup` returns canned validation results for known/unknown EPCs.

The navigation engine, signal processing, and resolve-before-navigate gate are all unit-tested against these fakes. Vendor adapters (Zebra, Impinj) and the real inventory integration are added behind the same interfaces once their SDK/API details are available — **not guessed at**.

---

## Vendor integration

Each RFID vendor is integrated via an adapter implementing the vendor-neutral `RfidReader` interface. Adapters translate vendor-specific reads into Orion's `RfidObservation` and are the **only** place a vendor SDK is imported. Adding a new vendor means writing a new adapter — not touching the core.

Vendor SDK access, credentials, and reader configuration follow the retailer's enterprise device and security requirements. No credentials or secrets are committed to this repository.

---

## Contributing

Before writing code, read [`CLAUDE.md`](./CLAUDE.md). It defines the architecture, the vendor-isolation boundary, the EPC model, error-handling and state conventions, testing expectations, and the things to avoid. In particular:

- Keep vendor-specific logic inside vendor adapters.
- Represent results as typed states — never signal failure with a bare `null`, empty list, or default value.
- Model loading / error / no-signal / success as distinct, exhaustive states.
- Don't ship placeholder or empty output as if it were real behavior.
- Add meaningful tests for non-trivial logic; don't guess external SDK or API behavior.
- Backend model changes ship with an Alembic migration. Never commit `.env` or real credentials.