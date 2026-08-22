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

This repository is an in-progress **Kotlin/Android rebuild**.

> Orion was previously prototyped as a React Native / Expo / Supabase application. That prototype is **deprecated** and has been removed. Do not reintroduce React Native, Expo, TypeScript, or a backend/database without a concrete, documented requirement. See [`CLAUDE.md`](./CLAUDE.md).

---

## Tech stack

- **Language:** Kotlin
- **UI:** Jetpack Compose
- **Async / streaming:** Kotlin Coroutines + Flow
- **Build:** Gradle
- **Target:** Android / enterprise PDT devices

RFID hardware is integrated through a vendor abstraction layer (Zebra, Impinj, …), with each vendor confined to its own adapter module.

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
    └── main/
        ├── java/.../
        │   ├── ui/              # Jetpack Compose screens + state
        │   ├── domain/          # vendor-neutral models
        │   ├── navigation/      # navigation engine
        │   ├── localization/    # proximity / direction estimation
        │   ├── signalprocessing/# RSSI filtering
        │   ├── rfid/            # RfidReader boundary, observations
        │   ├── inventory/       # EPC resolution / validation seam
        │   ├── integrations/    # vendor adapters
        │   │   ├── zebra/
        │   │   ├── impinj/
        │   │   └── fake/        # hardware-free reader + lookup for dev/tests
        │   └── session/         # find-and-navigate use cases
        └── res/                 # drawables, adaptive icons, etc.
```

Structure will evolve; folders are created when they earn their place, not to satisfy a theoretical layout.

---

## Getting started

> Scaffolding is in progress. This section will firm up as the Gradle project lands.

**Prerequisites**
- Android Studio (latest stable)
- JDK 17+
- An Android device or emulator (a physical PDT scanner is **not** required for core development — see below)

**Build & run**
```bash
./gradlew assembleDebug        # build
./gradlew installDebug         # install to a connected device/emulator
```

**Run the tests**
```bash
./gradlew test                 # unit tests (no hardware needed)
```

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