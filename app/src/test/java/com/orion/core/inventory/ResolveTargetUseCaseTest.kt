package com.orion.core.inventory

import com.orion.integrations.fake.FakeEpcLookup
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val KNOWN_EPC = "30245BFB8386AA80000186A1"
private const val KNOWN_NAME = "Blue Running Shoe M9"

// Well-formed SGTIN-96 (same partition/company prefix as KNOWN_EPC) that is not a key in
// FakeEpcLookup's map, so it exercises the NotFound path without a format concern.
private const val UNKNOWN_WELLFORMED_EPC = "30245BFB8386B8C0000F423F"

// Wrong header byte (0xFF instead of the required 0x30). Otherwise structurally valid.
private const val WRONG_HEADER_EPC = "FF23A352943FFE4000000001"

// Partition value 7 is not defined in the GS1 partition table (valid values are 0-6).
private const val INVALID_PARTITION_EPC = "303C00000000000000000000"

// Structurally valid bits (partition 1: 37-bit company prefix / 7-bit item reference) but the
// decoded company prefix (100000000000) has 12 digits, one more than partition 1's 11-digit limit.
private const val COMPANY_PREFIX_OVERFLOW_EPC = "3026E90EDD000A8000000001"

// Valid SGTIN-96 at the partition 0 boundary (40-bit/12-digit company prefix, 4-bit/1-digit item ref).
private const val PARTITION_0_BOUNDARY_EPC = "3023A352943FFE4000000001"

// Valid SGTIN-96 at the partition 6 boundary (20-bit/6-digit company prefix, 24-bit/7-digit item ref).
private const val PARTITION_6_BOUNDARY_EPC = "303BD08FE6259FC000000001"

class ResolveTargetUseCaseTest {

    private val useCase = ResolveTargetUseCase(FakeEpcLookup())

    @Test
    fun `known epc resolves`() = runTest {
        val result = useCase.resolve(FindInput.ScannedEpc(KNOWN_EPC))

        assertTrue(result is ResolveResult.Resolved)
        val resolved = result as ResolveResult.Resolved
        assertEquals(KNOWN_EPC, resolved.target.epc)
        assertEquals(KNOWN_NAME, resolved.target.displayName)
    }

    @Test
    fun `whitespace and lowercase input is normalized before lookup`() = runTest {
        val result = useCase.resolve(FindInput.TypedEpc("  ${KNOWN_EPC.lowercase()}  "))

        assertTrue(result is ResolveResult.Resolved)
        assertEquals(KNOWN_EPC, (result as ResolveResult.Resolved).target.epc)
    }

    @Test
    fun `well-formed but unknown epc is NotFound and carries the epc`() = runTest {
        val result = useCase.resolve(FindInput.TypedEpc(UNKNOWN_WELLFORMED_EPC))

        assertTrue(result is ResolveResult.NotFound)
        assertEquals(UNKNOWN_WELLFORMED_EPC, (result as ResolveResult.NotFound).epc)
    }

    @Test
    fun `malformed epc is Invalid and never reaches the lookup`() = runTest {
        val result = useCase.resolve(FindInput.TypedEpc("NOT-HEX!"))

        assertTrue(result is ResolveResult.Invalid)
    }

    @Test
    fun `odd-length epc is Invalid`() = runTest {
        val result = useCase.resolve(FindInput.TypedEpc("ABC"))

        assertTrue(result is ResolveResult.Invalid)
    }

    @Test
    fun `epc one character short of SGTIN-96 length is Invalid`() = runTest {
        val result = useCase.resolve(FindInput.TypedEpc(KNOWN_EPC.dropLast(1)))

        assertTrue(result is ResolveResult.Invalid)
    }

    @Test
    fun `epc one character longer than SGTIN-96 length is Invalid`() = runTest {
        val result = useCase.resolve(FindInput.TypedEpc(KNOWN_EPC + "0"))

        assertTrue(result is ResolveResult.Invalid)
    }

    @Test
    fun `wrong header byte is Invalid`() = runTest {
        val result = useCase.resolve(FindInput.TypedEpc(WRONG_HEADER_EPC))

        assertTrue(result is ResolveResult.Invalid)
    }

    @Test
    fun `undefined partition value is Invalid`() = runTest {
        val result = useCase.resolve(FindInput.TypedEpc(INVALID_PARTITION_EPC))

        assertTrue(result is ResolveResult.Invalid)
    }

    @Test
    fun `company prefix overflowing its partition digit count is Invalid`() = runTest {
        val result = useCase.resolve(FindInput.TypedEpc(COMPANY_PREFIX_OVERFLOW_EPC))

        assertTrue(result is ResolveResult.Invalid)
    }

    @Test
    fun `valid epc at the partition 0 boundary is well-formed`() = runTest {
        val result = useCase.resolve(FindInput.TypedEpc(PARTITION_0_BOUNDARY_EPC))

        // Not in FakeEpcLookup's map, but structurally valid, so it must reach the lookup.
        assertTrue(result is ResolveResult.NotFound)
    }

    @Test
    fun `valid epc at the partition 6 boundary is well-formed`() = runTest {
        val result = useCase.resolve(FindInput.TypedEpc(PARTITION_6_BOUNDARY_EPC))

        // Not in FakeEpcLookup's map, but structurally valid, so it must reach the lookup.
        assertTrue(result is ResolveResult.NotFound)
    }

    @Test
    fun `search input is rejected by resolve - callers must use search()`() = runTest {
        val result = useCase.resolve(FindInput.Search("shoe"))

        assertTrue(result is ResolveResult.Failure)
    }

    @Test
    fun `search returns matching epcs, case-insensitively`() = runTest {
        val results = useCase.search("blue running")

        assertEquals(2, results.size)
        assertTrue(results.any { it.epc == KNOWN_EPC })
    }

    @Test
    fun `search with no matches returns an empty list, not a failure`() = runTest {
        val results = useCase.search("nonexistent product")

        assertTrue(results.isEmpty())
    }
}
