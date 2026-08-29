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

    // ------------------------------------------------------------------
    // SGTIN-96 structural validation.
    //
    // Layout (96 bits / 24 hex chars):
    //   Header(8) Filter(3) Partition(3) CompanyPrefix+ItemReference(44) Serial(38)
    //
    // The partition value selects how the 44 bits between partition and serial
    // split into company prefix and item reference, per the GS1 SGTIN-96
    // partition table. Only structure is validated here — no inventory lookup.
    // ------------------------------------------------------------------

    private fun isWellFormedEpc(epc: String): Boolean {
        if (epc.length != SGTIN_HEX_LENGTH) return false
        val bits = hexToBits(epc) ?: return false

        val header = bits.substring(0, 8).toInt(radix = 2)
        if (header != SGTIN_HEADER) return false

        // Filter (bits 8-10): any value is acceptable.
        val partitionValue = bits.substring(11, 14).toInt(radix = 2)
        val partition = SGTIN_PARTITION_TABLE[partitionValue] ?: return false

        val companyPrefixStart = 14
        val companyPrefixEnd = companyPrefixStart + partition.companyPrefixBits
        val itemReferenceEnd = companyPrefixEnd + partition.itemRefBits

        val companyPrefix = bits.substring(companyPrefixStart, companyPrefixEnd).toLong(radix = 2)
        val itemReference = bits.substring(companyPrefixEnd, itemReferenceEnd).toLong(radix = 2)

        // Serial is the remaining 38 bits; any value is acceptable, so it is never decoded.

        return companyPrefix < tenToThePowerOf(partition.companyPrefixDigits) &&
            itemReference < tenToThePowerOf(partition.itemRefDigits)
    }

    /** Converts a single uppercase hex digit to its 4-bit binary string, or null if not hex. */
    private fun hexDigitToBits(c: Char): String? {
        val value = when (c) {
            in '0'..'9' -> c - '0'
            in 'A'..'F' -> c - 'A' + 10
            else -> return null
        }
        return value.toString(radix = 2).padStart(4, '0')
    }

    /** Converts a hex string into its full binary-string representation, or null if malformed. */
    private fun hexToBits(hex: String): String? {
        val bits = StringBuilder(hex.length * 4)
        for (c in hex) {
            bits.append(hexDigitToBits(c) ?: return null)
        }
        return bits.toString()
    }

    private fun tenToThePowerOf(exponent: Int): Long {
        var result = 1L
        repeat(exponent) { result *= 10 }
        return result
    }

    private companion object {
        const val SGTIN_HEX_LENGTH = 24
        const val SGTIN_HEADER = 0x30

        data class SgtinPartition(
            val companyPrefixBits: Int,
            val companyPrefixDigits: Int,
            val itemRefBits: Int,
            val itemRefDigits: Int,
        )

        val SGTIN_PARTITION_TABLE: Map<Int, SgtinPartition> = mapOf(
            0 to SgtinPartition(companyPrefixBits = 40, companyPrefixDigits = 12, itemRefBits = 4, itemRefDigits = 1),
            1 to SgtinPartition(companyPrefixBits = 37, companyPrefixDigits = 11, itemRefBits = 7, itemRefDigits = 2),
            2 to SgtinPartition(companyPrefixBits = 34, companyPrefixDigits = 10, itemRefBits = 10, itemRefDigits = 3),
            3 to SgtinPartition(companyPrefixBits = 30, companyPrefixDigits = 9, itemRefBits = 14, itemRefDigits = 4),
            4 to SgtinPartition(companyPrefixBits = 27, companyPrefixDigits = 8, itemRefBits = 17, itemRefDigits = 5),
            5 to SgtinPartition(companyPrefixBits = 24, companyPrefixDigits = 7, itemRefBits = 20, itemRefDigits = 6),
            6 to SgtinPartition(companyPrefixBits = 20, companyPrefixDigits = 6, itemRefBits = 24, itemRefDigits = 7),
        )
    }
}
