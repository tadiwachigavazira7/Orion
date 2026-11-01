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
- **`orion_fastAPI/`** — a deliberately narrow backend for **PDT device enrollment, verification, and revocation**. It answers "is this scanner trusted to run Orion, and for which retailer site?" It does not hold inventory, EPC, or RFID data; that stays with the retailer's systems and on the device.

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
- **Credentials:** Argon2id hashes only (never plaintext)
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
│   ├── api/                         # thin FastAPI routers (enrollment, devices)
│   ├── services/                    # enrollment / device / credential logic
│   ├── db/                          # SQLAlchemy models and session
│   ├── schemas.py, errors.py, config.py
├── alembic/                         # database migrations
├── scripts/seed_dev_data.py         # seeds a test organization + site
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
uv run python scripts/seed_dev_data.py # seed TEST-ORG-001 / TEST-SITE-001 (dev only)
uv run uvicorn orion_backend.main:app --reload
```

The API listens on `http://localhost:8000`.

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

Debug builds talk to the backend at `http://10.0.2.2:8000` (the emulator's alias for the host machine). Cleartext HTTP is allowed **only** for that host, and only in debug. To point a physical device at your machine, override the URL:

```bash
./gradlew installDebug -PORION_ENROLLMENT_BASE_URL=http://<your-lan-ip>:8000
```

Release builds have no backend URL configured and report enrollment as "not configured" rather than faking success.

### Run the tests

```bash
./gradlew test                         # Android unit tests (no hardware or backend needed)

cd orion_fastAPI
uv run pytest                          # backend tests (needs the test Postgres running)
```

Backend tests use `TEST_DATABASE_URL`, which defaults to `postgresql+asyncpg://orion:orion@localhost:55432/orion_test`. Set it if your test database is elsewhere. If Docker isn't running, every backend test fails with a connection error.

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