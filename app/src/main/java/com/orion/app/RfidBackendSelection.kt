// ============================================================
// app/RfidBackendSelection.kt
// Pure build-time decision (no Android deps, unit-testable): may this build use the
// simulated reader/lookup pair? Only an explicit FAKE backend in a debug build may.
// Everything else (release, "NONE", unknown values) is Unconfigured until a real
// reader adapter exists, so simulated data can never ship silently.
// ============================================================
package com.orion.app

sealed interface RfidSetup {
    data object Simulated : RfidSetup
    data object Unconfigured : RfidSetup
}

fun selectRfidSetup(backend: String, isDebugBuild: Boolean): RfidSetup =
    if (isDebugBuild && backend == "FAKE") RfidSetup.Simulated else RfidSetup.Unconfigured
