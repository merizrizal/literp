package com.literp.repository

import com.literp.common.ErrorCodes
import com.literp.test.TestDatabase
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.rxjava3.core.Vertx as RxVertx
import io.vertx.rxjava3.sqlclient.Pool
import io.vertx.rxjava3.sqlclient.Tuple
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PosOperationsRepositoryTest {
    private lateinit var coreVertx: Vertx
    private lateinit var rxVertx: RxVertx
    private lateinit var pool: Pool
    private lateinit var locationRepository: LocationRepository
    private lateinit var repository: PosOperationsRepository

    @BeforeAll
    fun setUp() {
        coreVertx = Vertx.vertx()
        rxVertx = RxVertx.newInstance(coreVertx)
        pool = TestDatabase.createPool(rxVertx)
        TestDatabase.assumeAvailable(pool)
        locationRepository = LocationRepository(pool)
        repository = PosOperationsRepository(pool)
    }

    @AfterAll
    fun tearDown() {
        if (::pool.isInitialized) {
            pool.rxClose().blockingAwait()
        }
        if (::coreVertx.isInitialized) {
            coreVertx.close().toCompletionStage().toCompletableFuture().get()
        }
    }

    @Test
    fun scopedTerminalReadsApplyGrantsToCountsDataAndSingleTerminalLookup() {
        val suffix = suffix()
        val locationA = createLocation("PTA-$suffix")
        val locationB = createLocation("PTB-$suffix")
        val terminalA1 = createTerminal(locationA, "PTA-1-$suffix", true)
        val terminalA2 = createTerminal(locationA, "PTA-2-$suffix", false)
        val terminalB = createTerminal(locationB, "PTB-1-$suffix", true)

        try {
            val firstPage = repository.listPosTerminals(
                page = 0,
                size = 1,
                sort = "terminalCode,asc",
                locationId = null,
                isActive = null,
                authorizedLocationIds = setOf(locationA)
            ).blockingGet()
            assertPagination(firstPage, page = 0, size = 1, totalElements = 2, totalPages = 2)
            assertEquals(1, terminalLocationIds(firstPage).size)
            assertTrue(terminalLocationIds(firstPage).all { it == locationA })

            val bothLocations = repository.listPosTerminals(
                page = 0,
                size = 10,
                sort = "terminalCode,asc",
                locationId = null,
                isActive = null,
                authorizedLocationIds = setOf(locationA, locationB)
            ).blockingGet()
            assertPagination(bothLocations, page = 0, size = 10, totalElements = 3, totalPages = 1)
            assertEquals(setOf(locationA, locationB), terminalLocationIds(bothLocations).toSet())

            val narrowed = repository.listPosTerminals(
                page = 0,
                size = 10,
                sort = "terminalCode,asc",
                locationId = locationA,
                isActive = null,
                authorizedLocationIds = setOf(locationA, locationB)
            ).blockingGet()
            assertPagination(narrowed, page = 0, size = 10, totalElements = 2, totalPages = 1)
            assertTrue(terminalLocationIds(narrowed).all { it == locationA })

            val activeOnly = repository.listPosTerminals(
                page = 0,
                size = 10,
                sort = "terminalCode,asc",
                locationId = locationA,
                isActive = true,
                authorizedLocationIds = setOf(locationA)
            ).blockingGet()
            assertPagination(activeOnly, page = 0, size = 10, totalElements = 1, totalPages = 1)
            assertEquals(listOf(terminalA1), terminalIds(activeOnly))

            val allowedTerminal = repository.getPosTerminal(terminalA2, setOf(locationA)).blockingGet()
            assertEquals(locationA, allowedTerminal.getString("locationId"))
            assertNotFound { repository.getPosTerminal(terminalB, setOf(locationA)).blockingGet() }
        } finally {
            listOf(terminalA1, terminalA2, terminalB).forEach(::deleteTerminal)
            deleteLocation(locationA)
            deleteLocation(locationB)
        }
    }

    @Test
    fun rejectsEmptyAndOutOfScopeLocationGrants() {
        assertFailsWith<PosOperationsScopeViolation> {
            repository.listPosTerminals(
                page = 0,
                size = 10,
                sort = "terminalCode,asc",
                locationId = null,
                isActive = null,
                authorizedLocationIds = emptySet()
            )
        }
        assertFailsWith<PosOperationsScopeViolation> {
            repository.listPosTerminals(
                page = 0,
                size = 10,
                sort = "terminalCode,asc",
                locationId = "outside-grant",
                isActive = null,
                authorizedLocationIds = setOf("authorized-location")
            )
        }
        assertFailsWith<PosOperationsScopeViolation> {
            repository.getPosTerminal("terminal-1", emptySet())
        }
    }

    private fun createLocation(code: String): String = locationRepository
        .createLocation(code, "POS terminal test $code", "WAREHOUSE", true, JsonObject())
        .blockingGet()
        .getString("locationId")

    private fun createTerminal(locationId: String, terminalCode: String, isActive: Boolean): String {
        val terminalId = UUID.randomUUID().toString()
        pool.preparedQuery(
            """
            INSERT INTO pos_terminal (terminal_id, location_id, terminal_code, device_name, is_active, created_at, updated_at)
            VALUES ($1, $2, $3, $4, $5, NOW(), NOW())
            """.trimIndent()
        ).rxExecute(
            Tuple.tuple()
                .addString(terminalId)
                .addString(locationId)
                .addString(terminalCode)
                .addString("Terminal $terminalCode")
                .addBoolean(isActive)
        ).blockingGet()
        return terminalId
    }

    private fun deleteTerminal(terminalId: String) {
        pool.preparedQuery("DELETE FROM pos_terminal WHERE terminal_id = $1")
            .rxExecute(Tuple.of(terminalId))
            .blockingGet()
    }

    private fun deleteLocation(locationId: String) {
        locationRepository.deleteLocation(locationId).blockingGet()
    }

    private fun assertNotFound(block: () -> Unit) {
        val error = assertFailsWith<Exception>(block = block)
        assertEquals(ErrorCodes.fromStatus(404), error.cause?.message)
    }

    private fun assertPagination(response: JsonObject, page: Int, size: Int, totalElements: Int, totalPages: Int) {
        val pagination = response.getJsonObject("pagination")
        assertEquals(page, pagination.getInteger("page"))
        assertEquals(size, pagination.getInteger("size"))
        assertEquals(totalElements, pagination.getInteger("totalElements"))
        assertEquals(totalPages, pagination.getInteger("totalPages"))
    }

    private fun terminalLocationIds(response: JsonObject): List<String> = response.getJsonArray("data")
        .filterIsInstance<JsonObject>()
        .map { it.getString("locationId") }

    private fun terminalIds(response: JsonObject): List<String> = response.getJsonArray("data")
        .filterIsInstance<JsonObject>()
        .map { it.getString("terminalId") }

    private fun suffix(): String = UUID.randomUUID().toString().replace("-", "").take(8).uppercase()
}
