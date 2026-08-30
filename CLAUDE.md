# CLAUDE.md

# Orion Development Guide

This file defines the architecture, engineering standards, and development rules for Orion.

Follow these guidelines unless explicitly instructed otherwise.

---

# 0. Status & Pivot Notice

**Orion has pivoted.** It is no longer a proprietary multi-piece hardware configuration.

Orion is now a **hardware-agnostic RFID software layer** that runs directly on top of the RFID/RSSI capabilities of enterprise PDT scanners the retailer already owns (Zebra, Impinj, etc.).

**This is a ground-up Kotlin/Android build.**

> Any prior Orion prototype built on React Native, Expo, TypeScript, FastAPI, or Supabase is **deprecated** and is not the reference for this codebase. Do not carry patterns, assumptions, or code from it into the Kotlin/Android application. If you find yourself reusing a decision from the old stack, stop and re-justify it against this document.

When this file and older code disagree, **this file wins.**

---

# 1. Product Overview

Orion is a **hardware-agnostic RFID software platform for real-time item navigation**.

Orion works with the RFID hardware and enterprise handheld/PDT scanners that retailers already own and use. Orion does **not** require customers to install proprietary Orion hardware.

The core Orion experience is:

```text
Retailer's Existing Infrastructure
        ↓
RFID Reader / PDT Scanner
        ↓
Vendor SDK / API
        ↓
Orion Hardware Abstraction Layer
        ↓
RFID Observation Processing
        ↓
Signal Processing
        ↓
Localization / Proximity Estimation
        ↓
Navigation Engine
        ↓
Compass-Style Android UI
        ↓
Employee finds target item
```

The primary technical problem Orion solves:

> **How can Orion interpret noisy RFID observations from existing enterprise hardware and reliably guide an employee to a specific tagged item?**

The core product is the **software intelligence layer**, not proprietary RFID hardware.

---

# 2. Product Scope

The current Orion MVP focuses on:

* Android/PDT application
* RFID hardware integration
* Hardware/vendor abstraction
* RFID observation processing
* RSSI interpretation
* Signal filtering
* Localization/proximity estimation
* Direction/navigation estimation (see capability caveat in §10)
* Compass-style employee UI
* Integration with existing retail inventory systems

Orion should minimize the amount of new infrastructure a retailer must install or maintain.

The default assumption is:

> **The retailer already has the hardware and inventory infrastructure. Orion integrates with it.**

Do not introduce proprietary hardware, a duplicate inventory system, or unnecessary cloud infrastructure unless explicitly required.

---

# 3. Core Product Principle

> **Orion should make existing RFID infrastructure intelligent rather than requiring retailers to replace or augment their infrastructure with proprietary Orion hardware.**

Architectural decisions should favor solutions that:

1. Work with existing enterprise infrastructure
2. Minimize customer installation requirements
3. Remain vendor-agnostic
4. Minimize retailer integration effort
5. Keep Orion's core intelligence independent from hardware vendors
6. Provide reliable real-time navigation

---

# 4. Technology Stack

## Android

* Kotlin
* Android SDK
* Jetpack Compose
* Kotlin Coroutines
* Kotlin Flow
* Gradle

Kotlin is the primary language for the Orion Android application.

Do not use React Native, Expo, or TypeScript for new Android application functionality. (See §0 — the prior RN/TS prototype is deprecated.)

## RFID

Orion supports multiple RFID hardware vendors through an abstraction layer.

Potential integrations include:

* Zebra RFID/PDT devices
* Impinj readers
* Other RFID vendors

Vendor-specific SDKs must remain isolated inside vendor integration modules.

## External Systems

Orion integrates with the retailer's existing systems rather than replacing them. These may include inventory management, RFID systems, enterprise device-management, authentication, and product/catalog systems.

The exact integration mechanism depends on the retailer's existing infrastructure and is not yet known (see §15).

---

# 5. Architecture

Orion uses a layered architecture with clean-architecture principles where they provide practical value. **Do not over-engineer.**

```text
┌──────────────────────────────────────┐
│              UI Layer                │
│       Jetpack Compose / Android      │
├──────────────────────────────────────┤
│        Application Layer             │
│   State / Use Cases / Coordination   │
├──────────────────────────────────────┤
│        Navigation Engine             │
├──────────────────────────────────────┤
│        Localization Engine            │
├──────────────────────────────────────┤
│        Signal Processing              │
├──────────────────────────────────────┤
│       RFID Domain / Abstraction       │
├──────────────────────────────────────┤
│       Vendor Integration Layer        │
├──────────────┬──────────────┬────────┤
│    Zebra     │    Impinj    │ Other  │
│    Adapter   │    Adapter   │ Adapter│
└──────────────┴──────────────┴────────┘
```

The most important architectural boundary:

> **Vendor-specific code must not leak into Orion's core domain logic.**

---

# 6. Hardware Abstraction Layer

Orion must be hardware-agnostic. Each supported vendor has an adapter that converts vendor-specific data into a standardized Orion representation.

```text
Zebra SDK → ZebraAdapter → RfidObservation
Impinj SDK → ImpinjAdapter → RfidObservation
```

Both then enter the same Orion processing pipeline.

```kotlin
data class RfidObservation(
    val epc: String,           // EPC is the tag identity used throughout Orion (see §6a)
    val readerId: String,
    val rssi: Double,          // normalized RSSI; see units note below
    val rawRssi: Int? = null,  // raw reader value (typically dBm) before normalization
    val timestamp: Long        // see timestamp source note below
)
```

**RSSI units:** Reader SDKs typically report RSSI as an integer (often dBm). Keep the raw value distinct from any normalized/smoothed value so units are never ambiguous. Do not silently mix raw and processed RSSI.

**Timestamp source:** Explicitly document whether a timestamp originates from the device clock or the reader clock. Temporal filtering (§9) depends on a consistent, documented time base. Do not mix clocks.

The model may evolve to preserve useful information across supported hardware. Potential fields: read frequency, antenna information, channel/frequency, vendor metadata where useful.

Do not throw away useful hardware information merely to force different vendors into an artificially identical model.

## 6a. EPC is the tag identity

The **EPC** is the canonical identifier for a tag everywhere in Orion — observations, engine, session, and UI all key on `epc`. Do not reintroduce `tagId`, `upc`, or a generic identifier in core logic.

Distinction that matters:

* An **EPC** identifies a single physical tagged item.
* A **UPC/GTIN** identifies a product class (many items share one UPC).

Orion targets a **single specific EPC**. Associate inputs (scan / typed / search) are treated as EPCs, not products — Orion does **not** perform product→tag resolution in core logic. See §15.

---

# 7. Vendor Integration Rules

Vendor-specific code belongs exclusively inside vendor integration modules.

Vendor adapters **may** contain: SDK init, reader connection/configuration, SDK event listeners, vendor lifecycle handling, vendor error handling, conversion to Orion observations.

Vendor adapters **must NOT** contain: localization algorithms, navigation algorithms, UI logic, vendor-independent signal processing, core business logic.

Adding a new vendor should require implementing a new adapter — not rewriting Orion's core.

---

# 8. RFID Data Pipeline

```text
Raw RFID Reads → Observation Normalization → Filtering → RSSI Processing
    → Signal Interpretation → Localization / Proximity → Navigation → UI
```

Each stage has a clear, single responsibility. Do not collapse the pipeline into one class or function.

**Anti-stub rule:** A pipeline stage is not "done" if it returns hardcoded, placeholder, or empty output in place of real processing. A stage that cannot yet do its job must surface that state explicitly (see §22), never fake a plausible-looking result. This applies equally to UI: an empty result is a first-class state, never a silent default (see §12).

**Resolve-before-interpret:** The interpretation pipeline (filter-to-target → signal processing → localization → navigation) starts only after a target EPC is resolved. Reads may be warmed earlier, but interpretation is gated on resolution. See §15.

---

# 9. RSSI and Signal Processing

RFID RSSI is noisy and environment-dependent.

Never assume:

```text
Higher RSSI = perfectly proportional to shorter distance
```

RSSI may be affected by noise, multipath, human bodies, shelving, metal, interference, reader/antenna characteristics, tag orientation, environmental change, and vendor-specific behavior.

Potential techniques: moving averages, exponential smoothing, median filters, outlier rejection, Kalman filtering, normalization, temporal aggregation, statistical estimation.

Start with the simplest approach that can be empirically validated. Do not introduce complex algorithms merely because they are mathematically interesting.

---

# 10. Localization Engine

The localization engine determines the user's relationship to the target RFID-tagged item.

Possible techniques: relative RSSI comparison, proximity estimation, direction estimation, trilateration, least-squares, Kalman filtering, motion estimation, probabilistic models.

## Capability caveat (read before building direction estimation)

RSSI-only sensing from a **single handheld antenna** is a hard problem. True **bearing/direction** generally requires antenna diversity, phase information, or user motion, and may not be achievable in v1.

> **v1 target: proximity + "hotter / colder" guidance** — reliably answer *"am I getting closer or farther?"*
>
> **Stretch goal (pending empirical validation): bearing/direction** — a literal compass arrow.

Do not generate a confident compass-arrow algorithm that the available signal cannot physically support. If direction estimation is attempted, it must degrade gracefully to proximity guidance when confidence is low.

The first objective is relative navigation, not absolute indoor positioning.

The localization engine must:

* Be independent of the UI
* Be independent of vendor SDKs
* Be testable without physical hardware (simulated observations)
* Accept standardized observations
* Produce predictable results for predictable inputs

---

# 11. Navigation Engine

The navigation engine converts localization information into actionable employee guidance.

Potential outputs: direction, bearing, proximity, confidence, target acquired, getting closer, getting farther, searching, signal unavailable.

```kotlin
data class NavigationState(
    val bearing: Double?,       // null when direction is unavailable/unsupported
    val proximity: Double?,     // null when proximity cannot be estimated
    val confidence: Double,
    val targetAcquired: Boolean
)
```

`null` outputs, low confidence, and "signal unavailable" are **distinct** conditions and must not collapse into the same code path (see §22). The navigation engine must not directly manipulate UI components.

---

# 12. Compass UI

The primary employee experience is a compass-style interface.

The UI may display: target item, SKU/product info, direction arrow, relative proximity, confidence, search status, RFID connection status, target-found state, error state.

## Mandatory UI states

The UI must model these as **distinct, exhaustive** states — ideally a `sealed interface` UI state — never collapsed and never faked with a default:

```text
Loading           (initializing / connecting)
Searching         (connected, seeking signal)
NoSignal          (connected, target signal unavailable — NOT the same as "not found")
Guiding           (proximity and/or bearing available)
TargetAcquired    (at/very near target)
Error             (hardware / integration / permission failure)
```

`NoSignal` is a first-class state, never an empty/placeholder rendering of `Guiding`.

The UI must NOT: talk to RFID SDKs, perform RSSI filtering, perform localization, contain vendor-specific logic, or contain core navigation algorithms. It consumes application/navigation state only.

---

# 13. Real-Time Processing and Concurrency

RFID observations arrive continuously and at high frequency.

Use Kotlin Coroutines and Flow for asynchronous/streaming workloads.

```text
Flow<RfidObservation> → Signal processing → Localization → Navigation state → UI
```

Dispatcher usage:

```text
Dispatchers.Main    → UI work
Dispatchers.IO      → Blocking / external I/O
Dispatchers.Default → CPU-intensive signal processing and calculations
```

## Backpressure

High-frequency reads feeding slower downstream stages is a backpressure problem — handle it deliberately, not per-feature guesswork:

* **UI-facing flows:** prefer `conflate()` or sampling — the UI only needs the latest state, not every read.
* **Processing flows:** use bounded `buffer()` / windowed aggregation; drop or aggregate duplicates rather than growing unbounded queues.
* Never let an unbounded queue accumulate observations faster than they can be processed.

Prefer structured concurrency. Handle cancellation correctly. RFID processing must respect the Android lifecycle.

Avoid: `GlobalScope`, unnecessary raw threads, blocking the main thread, unbounded coroutine creation, unsafe shared mutable state.

---

# 14. Android Lifecycle and Hardware Management

RFID hardware is not guaranteed to stay connected. Handle: reader connected/disconnected/unavailable, SDK init failure, permission failure, unsupported hardware, read errors, temporary signal loss, backgrounding, termination.

Release hardware resources correctly. Do not leak: activities, contexts, reader connections, SDK listeners, coroutine scopes, hardware resources.

---

# 15. External Retail Systems

The retailer's existing inventory management system is the **source of truth** for inventory. Orion should not create a duplicate inventory database unless explicitly required.

Potential external data: SKU, product, item, RFID tag, inventory status, store/warehouse location, product metadata.

## Integration seam (important)

Associate inputs are **EPCs**, not products. Orion does not resolve product→tag in core logic. The seam's job is to **validate an EPC** against the retailer's system (and optionally enrich it with a display name), not to look up a SKU. The retailer's actual API is not yet known, so do not guess or hardcode a specific API shape into core logic. Define the seam explicitly:

```kotlin
interface EpcLookup {
    /** Confirm the EPC exists in inventory; optionally return a display name. */
    suspend fun validate(epc: String): ResolveResult
    /** If EPC search is offered, it returns EPCs — never products. */
    suspend fun searchEpcs(query: String): List<EpcTarget>
}
```

Resolution results are a discriminated union that carries the offending EPC through, so a mistype, a genuinely-absent item, and a lookup failure are distinguishable (this is §22 applied to resolution):

```kotlin
sealed interface ResolveResult {
    data class Resolved(val target: EpcTarget) : ResolveResult
    data class NotFound(val epc: String) : ResolveResult    // valid format, unknown to inventory
    data class Invalid(val reason: String) : ResolveResult  // malformed EPC
    data class Failure(val reason: String) : ResolveResult  // lookup error (network, etc.)
}
```

* Domain and localization code depend only on this interface.
* Ship a **mock / local implementation** now so localization and navigation work can proceed unblocked.
* When real integration details arrive, add a concrete implementation behind the same interface — no core changes.

If word/product search (not EPC search) is ever required, that path — and only that path — reintroduces a product→EPC lookup. Keep the scan/typed-EPC paths free of it.

If integration details are missing, explicitly identify what information is required rather than inventing a protocol.

## Resolve-before-navigate gate

The RFID **interpretation** pipeline must not begin until a target EPC is resolved. This gate must be **structural, not advisory**: the function that starts navigation should be unreachable without a resolved EPC in hand (e.g. a `private` entry point only callable from a `Resolved` branch), so no code path can start interpreting reads without a target.

Reads from the hardware *may* start earlier as an optional latency optimization (warm up the reader on the resolution screen), but **interpretation** — filtering to the target EPC and producing `NavigationState` — starts only after resolution. See §8 and §13.

---

# 16. Backend

Orion currently does **not** require a dedicated backend. Do not introduce one merely because a conventional architecture would include it. The retailer's existing systems own the data/backend functionality they already provide.

A dedicated backend may become appropriate later for: configuration, device management, deployment, analytics, telemetry, remote config, authentication, cloud-based localization, cross-device sync — **only** when there is a concrete product requirement.

---

# 17. Data Ownership

```text
Retailer → owns inventory, product, employee data, and existing RFID infrastructure
Orion    → consumes required data, interprets signals, provides localization,
           navigation, and the employee experience
```

Do not duplicate retailer data without a clear technical or business reason. The retailer's systems remain the source of truth unless explicitly agreed otherwise.

---

# 18. Kotlin Standards

Prefer idiomatic Kotlin.

Use: `val` over `var` where possible, data classes, sealed classes/interfaces where appropriate, null safety, extension functions where useful, coroutines, Flow, immutable state where practical.

Avoid: Java-style boilerplate, `!!` without a strong invariant, global mutable state, unnecessary singletons, unnecessary abstractions.

Write Kotlin as Kotlin, not Java with different syntax.

---

# 19. General Coding Standards

Keep functions focused on one responsibility. Prefer composition over unnecessary abstraction. Avoid duplicated business logic. Use descriptive names. Prefer readable code over clever code. Avoid unchecked casts and overly broad types without a documented reason. Do not introduce a dependency or an abstraction without meaningful value.

---

# 20. Project Organization

Organize by feature and responsibility:

```text
app/
└── src/
    └── main/
        ├── java/.../
        │   ├── ui/
        │   ├── domain/
        │   ├── navigation/
        │   ├── localization/
        │   ├── signalprocessing/
        │   ├── rfid/
        │   ├── integrations/
        │   │   ├── zebra/
        │   │   ├── impinj/
        │   │   └── ...
        │   └── data/
        └── res/
```

The exact structure may evolve. Do not create folders just to satisfy a theoretical architecture.

---

# 21. Security

Security is a high priority.

Never hardcode: API keys, credentials, tokens, private keys, passwords, vendor credentials, signing credentials. Do not commit secrets. Never log sensitive credentials or auth tokens.

Follow retailer-integration and enterprise device-environment security requirements. Assume client-side apps can be inspected and manipulated. Use the minimum permissions required by Android and vendor SDKs.

---

# 22. Error Handling

Errors must be explicit and useful. Never silently swallow exceptions. Do not write empty `catch` blocks without a documented reason.

## Represent results as types, not conventions

Distinguish "broken" from "empty" from "low-confidence-but-valid" at the type level. Use `Result<T>` or a `sealed interface` — never a bare `null`, empty list, or default value to signal failure.

```kotlin
sealed interface LocalizationResult {
    data class Estimate(val state: NavigationState) : LocalizationResult   // valid, confidence carried inside
    data object SignalUnavailable : LocalizationResult                     // connected but no usable signal
    data class Failure(val reason: LocalizationError) : LocalizationResult // something is broken
}
```

A silent `emptyList()` on failure makes a broken pipeline indistinguishable from a genuine "no reads" state. That is a bug, not a default.

Distinguish, at the appropriate layer: hardware errors, integration errors, signal-quality problems, localization failures, navigation failures, external-system failures, UI errors.

---

# 23. Performance

Orion is a real-time application. Watch: RFID event frequency, signal-processing frequency, CPU, memory, battery, UI rendering, coroutine usage, hardware communication.

Avoid processing duplicate observations. Use aggregation/throttling where appropriate (see §13). Do not optimize prematurely — measure first. The main thread must stay responsive.

---

# 24. Testing

Testing is required for non-trivial functionality.

## Unit tests
Prioritize: RSSI filters, signal processing, localization algorithms, navigation calculations, data transformations, state transitions, vendor-independent domain logic. These must not require physical hardware.

```text
Input RFID observations → Localization engine → Expected localization result
```

## Integration tests
For vendor adapters (where practical), external-system integrations, hardware-abstraction boundaries, application services. Mock hardware when physical hardware is unavailable.

## UI tests
Cover critical flows: selecting an item, starting navigation, receiving observations, updating direction, finding the target, losing the RFID connection, recovering from hardware failure. Explicitly test the `NoSignal` and `Error` states (§12).

Every test should protect against a meaningful regression. Do not write tests purely to raise coverage numbers.

---

# 25. Git Standards

Make focused commits. Do not modify unrelated files. Use descriptive messages.

Before committing:
1. Check `git status`
2. Review `git diff`
3. Verify no secrets are included
4. Verify unrelated files were not modified
5. Run relevant tests

Keep the main branch stable.

---

# 26. AI Development Workflow

When implementing a feature:

1. Understand the existing architecture.
2. Identify which layer owns the behavior.
3. Determine whether the feature is vendor-specific or vendor-agnostic.
4. Keep vendor-specific logic inside the appropriate adapter.
5. Inspect existing code before creating new patterns.
6. Reuse existing abstractions where appropriate.
7. Identify concurrency and lifecycle implications.
8. Identify realistic failure cases.
9. Add meaningful tests for non-trivial logic.
10. Explain important architectural decisions and assumptions.
11. If required information is missing, search the repository or ask rather than guessing.

**Definition of Done:** a change is not done if it ships placeholder data, a fake empty state, or a silent failure path in place of real behavior. Every state (loading, error, empty/no-signal, success) must be reachable and correctly represented.

When multiple implementations are possible, prefer the simplest that remains: correct, vendor-agnostic, maintainable, testable, performant, secure.

Do not implement hypothetical future requirements unless explicitly requested. Every change should leave the codebase cleaner than before.

---

# 27. Things to Avoid

Do NOT:

* Introduce proprietary Orion RFID hardware
* Assume a specific RFID vendor in core logic (or that Zebra is the only vendor)
* Put vendor SDK calls in domain logic
* Put vendor-specific assumptions in localization
* Put RFID processing in UI components
* Ship placeholder/empty/hardcoded output as if it were real (see §8, §26)
* Signal failure with a bare `null`, empty list, or default value (see §22)
* Generate a bearing/compass algorithm the signal can't physically support (see §10)
* Perform heavy calculations on the main thread
* Use `GlobalScope`, create unnecessary raw threads, or leave flows unbounded
* Hardcode credentials or secrets
* Create a duplicate inventory system without justification
* Guess external API behavior or hardcode an unknown API shape into core logic
* Introduce a dedicated backend without a concrete requirement
* Introduce Supabase or another database without a concrete requirement
* Carry over patterns from the deprecated RN/FastAPI/Supabase prototype (see §0)
* Over-engineer the MVP or add dependencies without justification
* Build infrastructure before validating the core product

---

# 28. Architectural Decision Framework

When making an architectural decision, ask:

**Does this solve the core problem?** Can Orion reliably guide an employee to a tagged item using existing RFID infrastructure?

**Is it vendor-agnostic?** If not, isolate it inside a vendor adapter.

**Does it duplicate something the retailer already owns?** If yes, question whether it's necessary.

**Can it be processed locally?** If yes, prefer local processing when it improves latency, reliability, privacy, or simplicity.

**Are we solving a real problem?** Do not build infrastructure for hypothetical future requirements.

**Can it be tested without physical hardware?** If possible, keep core logic hardware-independent and testable with simulated observations.

**Is every state honestly represented?** Loading, error, empty/no-signal, and success must all be real, reachable, and distinct.

---

# 29. Core Orion Principle

> **Orion is the intelligence layer between existing enterprise RFID infrastructure and the employee.**

The retailer provides the infrastructure and inventory systems. Orion consumes the necessary data, interprets RFID signals, estimates the employee's relationship to the target, determines where the employee should move, and guides them to the item.

The core localization, signal-processing, and navigation technology must remain independent of the underlying hardware vendor.
