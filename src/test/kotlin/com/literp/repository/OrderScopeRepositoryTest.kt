package com.literp.repository

import com.literp.test.TestDatabase
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.rxjava3.sqlclient.Pool
import io.vertx.rxjava3.sqlclient.Tuple
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import io.vertx.rxjava3.core.Vertx as RxVertx

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderScopeRepositoryTest {
    private lateinit var coreVertx: Vertx
    private lateinit var rxVertx: RxVertx
    private lateinit var pool: Pool
    private lateinit var orderRepository: OrderProcessRepository
    private lateinit var locationRepository: LocationRepository
    private lateinit var scopeRepository: OrderScopeRepository

    @BeforeAll
    fun setUp() {
        coreVertx = Vertx.vertx()
        rxVertx = RxVertx.newInstance(coreVertx)
        pool = TestDatabase.createPool(rxVertx)
        TestDatabase.assumeAvailable(pool)

        orderRepository = OrderProcessRepository(pool)
        locationRepository = LocationRepository(pool)
        scopeRepository = OrderScopeRepository(pool)
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
    fun findLocationReturnsOnlyPersistedLocationAndExplicitNotFound() {
        val suffix = suffix()
        val location = createLocation("OSL-$suffix")
        val orderId = createOrder(location)

        try {
            assertEquals(location, scopeRepository.findLocation(orderId).blockingGet())
            assertTrue(scopeRepository.findLocation(UUID.randomUUID().toString()).isEmpty().blockingGet())
        } finally {
            deleteOrder(orderId)
            deleteLocation(location)
        }
    }

    @Test
    fun listAuthorizedOrdersAppliesScopeToCountDataAndRequestedLocation() {
        val suffix = suffix()
        val locationA = createLocation("OSA-$suffix")
        val locationB = createLocation("OSB-$suffix")
        val orderIds = listOf(
            createOrder(locationA),
            createOrder(locationA),
            createOrder(locationB)
        )

        try {
            val allowedPage = scopeRepository.listAuthorizedOrders(
                page = 1,
                size = 1,
                sort = "orderNumber,asc",
                status = null,
                salesChannel = null,
                locationId = null,
                authorizedLocationIds = setOf(locationA)
            ).blockingGet()
            assertPagination(allowedPage, page = 1, size = 1, totalElements = 2, totalPages = 2)
            assertEquals(1, locationIds(allowedPage).size)
            assertTrue(locationIds(allowedPage).all { it == locationA })

            val bothLocations = scopeRepository.listAuthorizedOrders(
                page = 0,
                size = 10,
                sort = "orderNumber,asc",
                status = null,
                salesChannel = null,
                locationId = null,
                authorizedLocationIds = setOf(locationA, locationB)
            ).blockingGet()
            assertPagination(bothLocations, page = 0, size = 10, totalElements = 3, totalPages = 1)
            assertEquals(setOf(locationA, locationB), locationIds(bothLocations).toSet())

            val narrowed = scopeRepository.listAuthorizedOrders(
                page = 0,
                size = 10,
                sort = "orderNumber,asc",
                status = null,
                salesChannel = null,
                locationId = locationA,
                authorizedLocationIds = setOf(locationA, locationB)
            ).blockingGet()
            assertPagination(narrowed, page = 0, size = 10, totalElements = 2, totalPages = 1)
            assertTrue(locationIds(narrowed).all { it == locationA })
        } finally {
            orderIds.forEach { deleteOrder(it) }
            deleteLocation(locationA)
            deleteLocation(locationB)
        }
    }

    @Test
    fun rejectsEmptyGrantsAndRequestedLocationOutsideScope() {
        assertFailsWith<OrderScopeViolation> {
            scopeRepository.listAuthorizedOrders(
                page = 0,
                size = 10,
                sort = "orderNumber,asc",
                status = null,
                salesChannel = null,
                locationId = null,
                authorizedLocationIds = emptySet()
            ).blockingGet()
        }

        assertFailsWith<OrderScopeViolation> {
            scopeRepository.listAuthorizedOrders(
                page = 0,
                size = 10,
                sort = "orderNumber,asc",
                status = null,
                salesChannel = null,
                locationId = "outside-grant",
                authorizedLocationIds = setOf("authorized-location")
            ).blockingGet()
        }
    }

    private fun createLocation(code: String): String {
        return locationRepository
            .createLocation(code, "Test Location $code", "WAREHOUSE", true, JsonObject())
            .blockingGet()
            .getString("locationId")
    }

    private fun createOrder(locationId: String): String {
        return orderRepository
            .createSalesOrderDraft("POS", locationId, null, "USD", null)
            .blockingGet()
            .getString("salesOrderId")
    }

    private fun deleteOrder(orderId: String) {
        pool.preparedQuery("DELETE FROM sales_order WHERE sales_order_id = $1")
            .rxExecute(Tuple.of(orderId))
            .blockingGet()
    }

    private fun deleteLocation(locationId: String) {
        locationRepository.deleteLocation(locationId).blockingGet()
    }

    private fun assertPagination(response: JsonObject, page: Int, size: Int, totalElements: Int, totalPages: Int) {
        val pagination = response.getJsonObject("pagination")
        assertEquals(page, pagination.getInteger("page"))
        assertEquals(size, pagination.getInteger("size"))
        assertEquals(totalElements, pagination.getInteger("totalElements"))
        assertEquals(totalPages, pagination.getInteger("totalPages"))
    }

    private fun locationIds(response: JsonObject): List<String> {
        return response.getJsonArray("data")
            .filterIsInstance<JsonObject>()
            .map { it.getString("locationId") }
    }

    private fun suffix(): String = UUID.randomUUID().toString().replace("-", "").take(8).uppercase()
}
