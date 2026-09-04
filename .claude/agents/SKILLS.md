# Orion Skills

This document defines the core technical skills and domain knowledge used to
develop Orion.

Agents should use these skills when appropriate, but should avoid unnecessary
complexity.

---

## 1. Kotlin

Primary language for Orion's Android application.

Agents should understand:

- Idiomatic Kotlin
- Classes, interfaces, and inheritance
- Data classes
- Sealed classes/interfaces
- Generics
- Null safety
- Collections
- Immutability
- Extension functions
- Exception handling
- Kotlin standard library

Prefer idiomatic Kotlin over Java-style Kotlin.

---

## 2. Android

Orion runs on enterprise Android PDT/scanner devices.

Agents should understand:

- Android lifecycle
- Activities
- Services when appropriate
- Context
- Permissions
- Configuration changes
- Foreground/background transitions
- Resource management
- Android SDK APIs
- Gradle builds

Hardware and SDK resources must be properly initialized and released.

---

## 3. Jetpack Compose

Orion uses a compass-style employee navigation UI.

Agents should understand:

- Composable functions
- State
- State hoisting
- `remember`
- `LaunchedEffect`
- `DisposableEffect`
- Recomposition
- Navigation
- UI state modeling

UI should display application/domain state rather than contain RFID processing,
localization, or vendor SDK logic.

---

## 4. Coroutines & Flow

RFID readings are continuous asynchronous data streams.

Agents should understand:

- Kotlin Coroutines
- Structured concurrency
- Coroutine scopes
- Cancellation
- `Dispatchers.Main`
- `Dispatchers.IO`
- `Dispatchers.Default`
- `Flow`
- `StateFlow`
- `SharedFlow`
- Flow operators

Prefer lifecycle-aware coroutines and avoid unnecessary raw threads or
`GlobalScope`.

Conceptually:

```text
RFID observations
        ↓
      Flow
        ↓
Signal Processing
        ↓
Localization
        ↓
Navigation State
        ↓
UI
5. RFID

Agents should understand:

RFID tags
EPC/tag IDs
RFID readers
Antennas
Read events
RSSI
Signal noise
Tag orientation
Multipath
Interference
Reader characteristics

RSSI is a noisy measurement and must not automatically be treated as exact
distance.

6. Vendor Integration

Orion is hardware/vendor agnostic.

Potential integrations include:

Zebra
Impinj
Other RFID vendors

Vendor-specific SDK code must be isolated behind adapters.

Vendor SDK
    ↓
Vendor Adapter
    ↓
Orion RFID Abstraction
    ↓
Core Orion Logic

The core localization, signal-processing, and navigation systems must not
depend directly on Zebra, Impinj, or another vendor's SDK.

Adding a new vendor should primarily require adding a new adapter.

7. Signal Processing

Agents should understand:

Moving averages
Exponential smoothing
Median filtering
Outlier rejection
Temporal aggregation
Noise
Variance
Confidence
Kalman filtering when justified

Start with the simplest method that produces reliable results.

Do not introduce advanced filtering without a real requirement.

8. Localization

Agents should understand:

Coordinate systems
Position estimation
Relative positioning
Distance estimation
Bearing
Trilateration
Least-squares estimation
Kalman filtering
Sensor fusion
Confidence estimation

Localization must remain independent of:

Vendor SDKs
UI
Android-specific presentation logic

Core localization algorithms should be testable without physical RFID
hardware whenever possible.

9. Navigation

Orion guides employees toward RFID-tagged items using a compass-style UI.

Agents should understand:

Device heading
Bearing
Relative direction
Angle normalization
Degrees/radians
Coordinate systems
Proximity
Confidence
Target acquisition

Navigation should consume vendor-independent localization data.

10. Android Sensors

If required, agents should understand:

Accelerometer
Gyroscope
Magnetometer
Device orientation
Heading
Sensor noise
Sensor fusion

Sensor fusion should only be introduced when required by the product.

11. Mathematics

Orion's signal processing and localization may require:

Linear algebra
Vectors
Matrices
Statistics
Probability
Least squares
Coordinate transformations
Numerical methods

Implement mathematics in deterministic, testable modules independent of UI
and vendor SDKs.

12. Networking & Enterprise Integration

Retailers already have inventory management systems.

Agents should understand:

REST APIs
HTTP
JSON
Serialization
Authentication
Authorization
TLS
Timeouts
Retries
Network failure handling

Never invent an external API contract.

Orion should consume retailer-provided systems rather than unnecessarily
recreating inventory infrastructure.

13. Testing

Agents should be capable of testing:

Signal processing
RSSI filtering
Localization
Navigation
Vendor adapters
Data transformations
State transitions
Error handling

Core logic should be testable without physical RFID hardware.

Hardware-dependent behavior should use interfaces, mocks, or fakes when
appropriate.

Test realistic failures such as:

No observations
Invalid observations
Noisy readings
Reader disconnection
Low confidence
Target unavailable
Lifecycle interruption
14. Dependency Injection

Understand and use dependency injection where it provides real value.

Useful candidates include:

RFID readers
Vendor adapters
Signal processors
Localization engines
Navigation engines
External services

Prefer simple constructor injection unless a framework is genuinely needed.

15. Architecture Patterns

Useful patterns include:

Adapter
Strategy
Dependency Injection
Repository
State Machine
Reactive streams

Use patterns to solve actual problems, not for the sake of using design
patterns.

16. Gradle & Android Tooling

Agents should understand:

Gradle
build.gradle.kts
settings.gradle.kts
Dependencies
Plugins
Build variants
Unit tests
Lint
Android builds

Use the project's existing build configuration and conventions.

17. Git

Agents should be comfortable with:

git status
git diff
git log
git branch
git add
git commit

Always inspect repository state before modifying code and inspect the diff
afterward.

Never overwrite unrelated work.

18. Security

Agents should understand:

Secure credential handling
Android permissions
TLS
Token handling
Secure storage
Input validation
Least privilege
Logging hygiene

Never commit:

API keys
Passwords
Tokens
Private keys
Vendor credentials
Signing credentials
Other secrets

Never log sensitive credentials or authentication tokens.

Core Principle

Orion's hardware integrations should be replaceable without rewriting the
core intelligence.

Zebra ──────┐
Impinj ─────┤
Other ──────┤
            ↓
     RFID Abstraction
            ↓
     Signal Processing
            ↓
       Localization
            ↓
        Navigation
            ↓
           UI

Hardware can change. Orion's core intelligence should remain stable.