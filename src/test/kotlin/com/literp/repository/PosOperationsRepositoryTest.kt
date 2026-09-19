package com.literp.repository

import com.literp.common.ErrorCodes
import com.literp.test.TestDatabase
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.rxjava3.sqlclient.Pool
import io.vertx.rxjava3.sqlclient.Tuple
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import io.vertx.rxjava3.core.Vertx as RxVertx

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
    fun terminalAdministrationCreatesReplaysUpdatesAndDeactivatesSafely() {
        val suffix = suffix()
        val locationId = createLocation("PTM-$suffix")
        val actorSubject = "repo-terminal-admin-$suffix"
        val organizationId = "repo-org-$suffix"
        val idempotencyKey = "create-terminal-$suffix"
        val terminalCode = "PTM-1-$suffix"
        var terminalId: String? = null

        try {
            val created = repository.createPosTerminal(
                locationId = locationId,
                terminalCode = terminalCode,
                deviceName = "Repository terminal $suffix",
                idempotencyKey = idempotencyKey,
                actorSubject = actorSubject,
                organizationId = organizationId,
                authorizedLocationIds = setOf(locationId)
            ).blockingGet()
            terminalId = created.getString("terminalId")
            val createdTerminalId = requireNotNull(terminalId)
            assertTrue(created.getBoolean("isActive"))
            assertEquals(1L, commandLedgerCount(organizationId, actorSubject, locationId, idempotencyKey))

            val replay = repository.createPosTerminal(
                locationId = locationId,
                terminalCode = terminalCode,
                deviceName = "Repository terminal $suffix",
                idempotencyKey = idempotencyKey,
                actorSubject = actorSubject,
                organizationId = organizationId,
                authorizedLocationIds = setOf(locationId)
            ).blockingGet()
            assertEquals(createdTerminalId, replay.getString("terminalId"))
            assertEquals(created.getString("createdAt"), replay.getString("createdAt"))
            assertEquals(1L, commandLedgerCount(organizationId, actorSubject, locationId, idempotencyKey))

            assertFailureMessage {
                repository.createPosTerminal(
                    locationId = locationId,
                    terminalCode = "PTM-CHANGED-$suffix",
                    deviceName = "Repository terminal $suffix",
                    idempotencyKey = idempotencyKey,
                    actorSubject = actorSubject,
                    organizationId = organizationId,
                    authorizedLocationIds = setOf(locationId)
                ).blockingGet()
            }.also { assertEquals("Idempotency key conflict", it) }

            val updated = repository.updatePosTerminal(
                terminalId = createdTerminalId,
                terminalCode = "PTM-U-$suffix",
                deviceName = "Updated repository terminal $suffix",
                authorizedLocationIds = setOf(locationId)
            ).blockingGet()
            assertEquals("PTM-U-$suffix", updated.getString("terminalCode"))
            assertEquals("Updated repository terminal $suffix", updated.getString("deviceName"))

            val deactivated = repository.deactivatePosTerminal(createdTerminalId, setOf(locationId)).blockingGet()
            assertFalse(deactivated.getBoolean("isActive"))
            val repeatedDeactivation = repository.deactivatePosTerminal(createdTerminalId, setOf(locationId)).blockingGet()
            assertFalse(repeatedDeactivation.getBoolean("isActive"))
        } finally {
            deleteCommandLedger(organizationId, actorSubject, locationId, idempotencyKey)
            terminalId?.let(::deleteTerminal)
            deleteLocation(locationId)
        }
    }

    @Test
    fun shiftOpeningReplaysUsesTrustedActorAndCurrentLookupReturnsOpenShift() {
        val suffix = suffix()
        val locationId = createLocation("PSO-$suffix")
        val terminalId = createTerminal(locationId, "PSO-1-$suffix", true)
        val actorSubject = "repo-shift-owner-$suffix-${"x".repeat(40)}"
        val organizationId = "repo-shift-org-$suffix"
        val idempotencyKey = "open-shift-$suffix"
        var shiftId: String? = null

        try {
            val opened = repository.openPosShift(
                terminalId = terminalId,
                openingBalance = "250.00",
                currency = "USD",
                idempotencyKey = idempotencyKey,
                actorSubject = actorSubject,
                organizationId = organizationId,
                authorizedLocationIds = setOf(locationId)
            ).blockingGet()
            shiftId = opened.getString("shiftId")
            assertTrue(isShiftReconcilable(requireNotNull(shiftId)))
            assertEquals(terminalId, opened.getString("terminalId"))
            assertEquals(actorSubject, opened.getString("operatorId"))
            assertEquals("USD", opened.getString("currency"))
            assertEquals("OPEN", opened.getString("status"))
            assertEquals(BigDecimal("250.00"), decimalValue(opened, "openingBalance"))
            assertEquals(null, decimalValue(opened, "closingBalance"))
            assertEquals(null, decimalValue(opened, "expectedCash"))
            assertEquals(null, decimalValue(opened, "cashVariance"))
            assertEquals(1L, commandLedgerCount(
                organizationId,
                actorSubject,
                terminalId,
                idempotencyKey,
                operationId = "openPosShift"
            ))

            val current = repository.getCurrentPosShift(terminalId, setOf(locationId)).blockingGet()
            assertEquals(shiftId, current.getString("shiftId"))

            val replay = repository.openPosShift(
                terminalId,
                "250.00",
                "USD",
                idempotencyKey,
                actorSubject,
                organizationId,
                setOf(locationId)
            ).blockingGet()
            assertEquals(shiftId, replay.getString("shiftId"))
            assertEquals(opened.getString("openedAt"), replay.getString("openedAt"))
            assertEquals(1L, commandLedgerCount(
                organizationId,
                actorSubject,
                terminalId,
                idempotencyKey,
                operationId = "openPosShift"
            ))

            assertFailureMessage {
                repository.openPosShift(
                    terminalId,
                    "251.00",
                    "USD",
                    idempotencyKey,
                    actorSubject,
                    organizationId,
                    setOf(locationId)
                ).blockingGet()
            }.also { assertEquals("Idempotency key conflict", it) }

            assertFailureMessage {
                repository.openPosShift(
                    terminalId,
                    "300.00",
                    "USD",
                    "different-$suffix",
                    actorSubject,
                    organizationId,
                    setOf(locationId)
                ).blockingGet()
            }.also { assertEquals("Terminal already has an open shift", it) }
        } finally {
            deleteCommandLedger(
                organizationId,
                actorSubject,
                terminalId,
                idempotencyKey,
                operationId = "openPosShift"
            )
            shiftId?.let(::deleteShift)
            deleteTerminal(terminalId)
            deleteLocation(locationId)
        }
    }

    @Test
    fun concurrentCreateRetriesWithTheSameKeyProduceOneTerminal() {
        val suffix = suffix()
        val locationId = createLocation("PTX-$suffix")
        val actorSubject = "repo-terminal-concurrent-$suffix"
        val organizationId = "repo-concurrent-org-$suffix"
        val idempotencyKey = "create-concurrent-$suffix"
        val terminalCode = "PTX-1-$suffix"
        var terminalId: String? = null

        try {
            fun createTerminalRequest(): JsonObject = repository.createPosTerminal(
                locationId = locationId,
                terminalCode = terminalCode,
                deviceName = "Concurrent repository terminal $suffix",
                idempotencyKey = idempotencyKey,
                actorSubject = actorSubject,
                organizationId = organizationId,
                authorizedLocationIds = setOf(locationId)
            ).blockingGet()

            val first = CompletableFuture.supplyAsync { createTerminalRequest() }
            val second = CompletableFuture.supplyAsync { createTerminalRequest() }
            val responses = listOf(first.get(), second.get())
            val terminalIds = responses.map { it.getString("terminalId") }.toSet()
            assertEquals(1, terminalIds.size)
            terminalId = terminalIds.single()
            assertEquals(1L, commandLedgerCount(organizationId, actorSubject, locationId, idempotencyKey))
        } finally {
            deleteCommandLedger(organizationId, actorSubject, locationId, idempotencyKey)
            terminalId?.let(::deleteTerminal) ?: deleteTerminalByCode(terminalCode)
            deleteLocation(locationId)
        }
    }

    @Test
    fun duplicateTerminalCodeRollsBackTheCreationLedgerClaim() {
        val suffix = suffix()
        val locationId = createLocation("PTC-$suffix")
        val existingTerminalId = createTerminal(locationId, "PTC-1-$suffix", true)
        val actorSubject = "repo-terminal-conflict-$suffix"
        val organizationId = "repo-conflict-org-$suffix"
        val idempotencyKey = "create-conflict-$suffix"

        try {
            assertFailureMessage {
                repository.createPosTerminal(
                    locationId = locationId,
                    terminalCode = "PTC-1-$suffix",
                    deviceName = "Conflicting terminal",
                    idempotencyKey = idempotencyKey,
                    actorSubject = actorSubject,
                    organizationId = organizationId,
                    authorizedLocationIds = setOf(locationId)
                ).blockingGet()
            }.also { assertEquals("Terminal code already exists", it) }
            assertEquals(0L, commandLedgerCount(organizationId, actorSubject, locationId, idempotencyKey))
        } finally {
            deleteCommandLedger(organizationId, actorSubject, locationId, idempotencyKey)
            deleteTerminal(existingTerminalId)
            deleteLocation(locationId)
        }
    }

    @Test
    fun deactivationRejectsAnOpenShiftAndKeepsTerminalActive() {
        val suffix = suffix()
        val locationId = createLocation("PTS-$suffix")
        val terminalId = createTerminal(locationId, "PTS-1-$suffix", true)
        val shiftId = createOpenShift(terminalId, "repo-shift-$suffix")

        try {
            assertFailureMessage {
                repository.deactivatePosTerminal(terminalId, setOf(locationId)).blockingGet()
            }.also { assertEquals("Terminal has an open shift", it) }
            assertTrue(repository.getPosTerminal(terminalId, setOf(locationId)).blockingGet().getBoolean("isActive"))
        } finally {
            deleteShift(shiftId)
            deleteTerminal(terminalId)
            deleteLocation(locationId)
        }
    }

    @Test
    fun currentShiftDoesNotFabricateLegacyTrustFields() {
        val suffix = suffix()
        val locationId = createLocation("PSL-$suffix")
        val terminalId = createTerminal(locationId, "PSL-1-$suffix", true)
        val shiftId = createOpenShift(terminalId, "legacy-operator-$suffix")

        try {
            assertFalse(isShiftReconcilable(shiftId))
            val current = repository.getCurrentPosShift(terminalId, setOf(locationId)).blockingGet()
            assertEquals(shiftId, current.getString("shiftId"))
            assertEquals(null, current.getString("currency"))
            assertEquals(null, decimalValue(current, "expectedCash"))
            assertEquals(null, decimalValue(current, "cashVariance"))
            assertEquals(null, current.getString("closedBy"))
        } finally {
            deleteShift(shiftId)
            deleteTerminal(terminalId)
            deleteLocation(locationId)
        }
    }

    @Test
    fun shiftClosingRejectsLegacyUnreconcilableStateWithoutClaimingACommand() {
        val suffix = suffix()
        val locationId = createLocation("PSL-CLOSE-$suffix")
        val terminalId = createTerminal(locationId, "PSL-CLOSE-1-$suffix", true)
        val actorSubject = "legacy-shift-owner-$suffix"
        val shiftId = createOpenShift(terminalId, actorSubject)
        val organizationId = "legacy-close-org-$suffix"
        val idempotencyKey = "legacy-close-$suffix"

        try {
            assertFailureMessage {
                repository.closePosShift(
                    shiftId = shiftId,
                    closingBalance = "0.00",
                    idempotencyKey = idempotencyKey,
                    actorSubject = actorSubject,
                    organizationId = organizationId,
                    authorizedLocationIds = setOf(locationId)
                ).blockingGet()
            }.also { assertEquals("POS shift is not reconciliable", it) }
            assertEquals(
                0L,
                commandLedgerCount(
                    organizationId,
                    actorSubject,
                    shiftId,
                    idempotencyKey,
                    operationId = "closePosShift"
                )
            )
        } finally {
            deleteCommandLedger(
                organizationId,
                actorSubject,
                shiftId,
                idempotencyKey,
                operationId = "closePosShift"
            )
            deleteShift(shiftId)
            deleteTerminal(terminalId)
            deleteLocation(locationId)
        }
    }

    @Test
    fun shiftOpeningRejectsExcessPrecisionAndNonUppercaseCurrency() {
        assertFailureMessage {
            repository.openPosShift(
                "terminal-1",
                "1.001",
                "USD",
                "precision-key",
                "actor",
                "organization",
                setOf("authorized-location")
            ).blockingGet()
        }.also { assertEquals("openingBalance must have at most two decimal places", it) }

        assertFailureMessage {
            repository.openPosShift(
                "terminal-1",
                "1.00",
                "usd",
                "currency-key",
                "actor",
                "organization",
                setOf("authorized-location")
            ).blockingGet()
        }.also { assertEquals("currency must be three uppercase letters", it) }
    }

    @Test
    fun concurrentShiftOpeningWithSameKeyProducesOneOpenShift() {
        val suffix = suffix()
        val locationId = createLocation("PSC-$suffix")
        val terminalId = createTerminal(locationId, "PSC-1-$suffix", true)
        val actorSubject = "repo-shift-concurrent-$suffix"
        val organizationId = "repo-shift-concurrent-org-$suffix"
        val idempotencyKey = "open-concurrent-$suffix"
        var shiftId: String? = null

        try {
            fun openShift(): JsonObject = repository.openPosShift(
                terminalId,
                "300.00",
                "EUR",
                idempotencyKey,
                actorSubject,
                organizationId,
                setOf(locationId)
            ).blockingGet()

            val first = CompletableFuture.supplyAsync { openShift() }
            val second = CompletableFuture.supplyAsync { openShift() }
            val responses = listOf(first.get(), second.get())
            val shiftIds = responses.map { it.getString("shiftId") }.toSet()
            assertEquals(1, shiftIds.size)
            shiftId = shiftIds.single()
            assertEquals(1L, commandLedgerCount(
                organizationId,
                actorSubject,
                terminalId,
                idempotencyKey,
                operationId = "openPosShift"
            ))
            assertEquals(1L, openShiftCount(terminalId))
        } finally {
            deleteCommandLedger(
                organizationId,
                actorSubject,
                terminalId,
                idempotencyKey,
                operationId = "openPosShift"
            )
            shiftId?.let(::deleteShift)
            deleteTerminal(terminalId)
            deleteLocation(locationId)
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

    private fun createOpenShift(terminalId: String, operatorId: String): String {
        val shiftId = UUID.randomUUID().toString()
        pool.preparedQuery(
            """
            INSERT INTO pos_shift (
                shift_id, terminal_id, operator_id, shift_date, shift_number,
                opened_at, opening_balance, status, created_at
            )
            VALUES ($1, $2, $3, CURRENT_DATE, 1, NOW(), 0, 'OPEN', NOW())
            """.trimIndent()
        ).rxExecute(Tuple.of(shiftId, terminalId, operatorId)).blockingGet()
        return shiftId
    }

    private fun isShiftReconcilable(shiftId: String): Boolean = pool.preparedQuery(
        "SELECT is_reconcilable FROM pos_shift WHERE shift_id = $1"
    ).rxExecute(Tuple.of(shiftId))
        .blockingGet()
        .first()
        .getBoolean("is_reconcilable")

    private fun openShiftCount(terminalId: String): Long = pool.preparedQuery(
        "SELECT COUNT(*) AS total FROM pos_shift WHERE terminal_id = $1 AND status = 'OPEN'"
    ).rxExecute(Tuple.of(terminalId))
        .blockingGet()
        .first()
        .getLong("total")

    private fun deleteShift(shiftId: String) {
        pool.preparedQuery("DELETE FROM pos_shift WHERE shift_id = $1")
            .rxExecute(Tuple.of(shiftId))
            .blockingGet()
    }

    private fun commandLedgerCount(
        organizationId: String,
        actorSubject: String,
        targetId: String,
        idempotencyKey: String,
        operationId: String = "createPosTerminal"
    ): Long = pool.preparedQuery(
        """
        SELECT COUNT(*) AS total
        FROM pos_command_ledger
        WHERE organization_id = $1 AND actor_subject = $2 AND operation_id = $3
          AND target_id = $4 AND idempotency_key = $5
        """.trimIndent()
    ).rxExecute(Tuple.of(organizationId, actorSubject, operationId, targetId, idempotencyKey))
        .blockingGet()
        .first()
        .getLong("total")

    private fun deleteCommandLedger(
        organizationId: String,
        actorSubject: String,
        targetId: String,
        idempotencyKey: String,
        operationId: String = "createPosTerminal"
    ) {
        pool.preparedQuery(
            """
            DELETE FROM pos_command_ledger
            WHERE organization_id = $1 AND actor_subject = $2 AND operation_id = $3
              AND target_id = $4 AND idempotency_key = $5
            """.trimIndent()
        ).rxExecute(Tuple.of(organizationId, actorSubject, operationId, targetId, idempotencyKey)).blockingGet()
    }

    private fun deleteTerminalByCode(terminalCode: String) {
        pool.preparedQuery("DELETE FROM pos_terminal WHERE terminal_code = $1")
            .rxExecute(Tuple.of(terminalCode))
            .blockingGet()
    }

    private fun deleteTerminal(terminalId: String) {
        pool.preparedQuery("DELETE FROM pos_terminal WHERE terminal_id = $1")
            .rxExecute(Tuple.of(terminalId))
            .blockingGet()
    }

    private fun deleteLocation(locationId: String) {
        locationRepository.deleteLocation(locationId).blockingGet()
    }

    private fun decimalValue(response: JsonObject, fieldName: String): BigDecimal? =
        response.getValue(fieldName)?.toString()?.toBigDecimal()

    private fun assertFailureMessage(block: () -> Unit): String {
        val error = assertFailsWith<Exception>(block = block)
        return error.cause?.message ?: error.message.orEmpty()
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
