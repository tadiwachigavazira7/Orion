package com.orion.core.inventory

import com.orion.integrations.fake.FakeEpcLookup
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val KNOWN_EPC = "3034F4A9C0"
private const val KNOWN_NAME = "Blue Running Shoe M9"

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
        val result = useCase.resolve(FindInput.TypedEpc("  3034f4a9c0  "))

        assertTrue(result is ResolveResult.Resolved)
        assertEquals(KNOWN_EPC, (result as ResolveResult.Resolved).target.epc)
    }

    @Test
    fun `well-formed but unknown epc is NotFound and carries the epc`() = runTest {
        val result = useCase.resolve(FindInput.TypedEpc("DEADBEEF"))

        assertTrue(result is ResolveResult.NotFound)
        assertEquals("DEADBEEF", (result as ResolveResult.NotFound).epc)
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
