// ============================================================
// integrations/fake/FakeEpcLookup.kt   — canned validation for dev + tests
// ============================================================
package com.orion.integrations.fake

import com.orion.core.inventory.EpcLookup
import com.orion.core.inventory.EpcTarget
import com.orion.core.inventory.ResolveResult

class FakeEpcLookup(
    private val known: Map<String, String?> = mapOf(   // epc -> optional display name
        "3034F4A9C0" to "Blue Running Shoe M9",
        "3034F4A9C1" to "Blue Running Shoe M10"
    )
) : EpcLookup {
    override suspend fun validate(epc: String): ResolveResult =
        if (known.containsKey(epc)) ResolveResult.Resolved(EpcTarget(epc, known[epc]))
        else ResolveResult.NotFound(epc)

    override suspend fun searchEpcs(query: String): List<EpcTarget> =
        known.filter { (_, name) -> name?.contains(query, ignoreCase = true) == true }
             .map { (epc, name) -> EpcTarget(epc, name) }
}
