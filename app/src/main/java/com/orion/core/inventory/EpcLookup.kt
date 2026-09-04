// ============================================================
// core/inventory/EpcLookup.kt
// Inputs are EPCs. Job is VALIDATION against the retailer's system,
// not product resolution. Retailer system is source of truth (CLAUDE.md §15).
// ============================================================
package com.orion.core.inventory

data class EpcTarget(
    val epc: String,
    val displayName: String? = null   // e.g. product name for the UI header, if known
)

sealed interface ResolveResult {
    data class Resolved(val target: EpcTarget) : ResolveResult
    data class NotFound(val epc: String) : ResolveResult       // valid format, unknown to inventory
    data class Invalid(val reason: String) : ResolveResult      // malformed EPC
    data class Failure(val reason: String) : ResolveResult      // lookup error (network, etc.)
}

interface EpcLookup {
    /** Confirm the EPC exists; optionally return a display name. */
    suspend fun validate(epc: String): ResolveResult
    /** If EPC search is offered, it returns EPCs — never products. */
    suspend fun searchEpcs(query: String): List<EpcTarget>
}
