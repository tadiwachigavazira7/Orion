// ============================================================
// core/inventory/ResolveTargetUseCase.kt
// The GATE input. All inputs are EPCs → validate → single target.
// ============================================================
package com.orion.core.inventory

sealed interface FindInput {
    data class ScannedEpc(val epc: String) : FindInput   // barcode encodes an EPC
    data class TypedEpc(val epc: String) : FindInput      // typed directly
    data class Search(val query: String) : FindInput      // searches EPCs; associate picks one
}

class ResolveTargetUseCase(private val lookup: EpcLookup) {

    suspend fun resolve(input: FindInput): ResolveResult = when (input) {
        is FindInput.ScannedEpc -> validate(input.epc)
        is FindInput.TypedEpc   -> validate(input.epc)
        is FindInput.Search     -> ResolveResult.Failure("Use search() for search input")
    }

    suspend fun search(query: String): List<EpcTarget> = lookup.searchEpcs(query)

    private suspend fun validate(rawEpc: String): ResolveResult {
        val epc = rawEpc.trim().uppercase()
        if (!isWellFormedEpc(epc)) return ResolveResult.Invalid("Malformed EPC: $rawEpc")
        return lookup.validate(epc)
    }

    // PLACEHOLDER validation — replace with the real EPC format spec (e.g. SGTIN-96).
    private fun isWellFormedEpc(epc: String): Boolean =
        epc.isNotEmpty() && epc.all { it.isDigit() || it in 'A'..'F' } && epc.length % 2 == 0
}
