package com.literp.repository

import com.literp.common.ErrorCodes
import com.literp.test.TestDatabase
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.rxjava3.core.Vertx as RxVertx
import io.vertx.rxjava3.sqlclient.Pool
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MasterDataRepositoryTest {
    private lateinit var coreVertx: Vertx
    private lateinit var rxVertx: RxVertx
    private lateinit var pool: Pool
    private lateinit var uomRepository: UnitOfMeasureRepository
    private lateinit var productRepository: ProductRepository
    private lateinit var variantRepository: ProductVariantRepository
    private lateinit var locationRepository: LocationRepository

    @BeforeAll
    fun setUp() {
        coreVertx = Vertx.vertx()
        rxVertx = RxVertx.newInstance(coreVertx)
        pool = TestDatabase.createPool(rxVertx)
        TestDatabase.assumeAvailable(pool)

        uomRepository = UnitOfMeasureRepository(pool)
        productRepository = ProductRepository(pool)
        variantRepository = ProductVariantRepository(pool)
        locationRepository = LocationRepository(pool)
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
    fun duplicateChecksReflectCreatedMasterData() {
        val suffix = suffix()
        val uomCode = "T$suffix"
        val productSku = "P-$suffix"
        val variantSku = "V-$suffix"
        val locationCode = "L-$suffix"

        assertFalse(uomRepository.checkCodeExists(uomCode).blockingGet())
        val uom = uomRepository.createUnitOfMeasure(uomCode, "Test UOM $suffix", null).blockingGet()
        assertTrue(uomRepository.checkCodeExists(uomCode).blockingGet())
        uomRepository.deleteUnitOfMeasure(uom.getString("uomId")).blockingGet()

        assertFalse(productRepository.checkSkuExists(productSku).blockingGet())
        val product = productRepository
            .createProduct(productSku, "Test Product $suffix", "STOCK", TestDatabase.SEED_UOM_UNIT, true, JsonObject())
            .blockingGet()
        assertTrue(productRepository.checkSkuExists(productSku).blockingGet())

        assertFalse(variantRepository.checkSkuExists(variantSku).blockingGet())
        val variant = variantRepository
            .createProductVariant(product.getString("productId"), variantSku, "Test Variant $suffix", true, JsonObject())
            .blockingGet()
        assertTrue(variantRepository.checkSkuExists(variantSku).blockingGet())
        variantRepository.deleteProductVariant(product.getString("productId"), variant.getString("variantId")).blockingGet()
        productRepository.deleteProduct(product.getString("productId")).blockingGet()

        assertFalse(locationRepository.checkCodeExists(locationCode).blockingGet())
        val location = locationRepository
            .createLocation(locationCode, "Test Location $suffix", "WAREHOUSE", true, JsonObject())
            .blockingGet()
        assertTrue(locationRepository.checkCodeExists(locationCode).blockingGet())
        locationRepository.deleteLocation(location.getString("locationId")).blockingGet()
    }

    @Test
    fun deleteAndNotFoundBehaviorIsStable() {
        val suffix = suffix()

        val uom = uomRepository.createUnitOfMeasure("D$suffix", "Delete UOM $suffix", null).blockingGet()
        val uomId = uom.getString("uomId")
        uomRepository.deleteUnitOfMeasure(uomId).blockingGet()
        assertStatusError(404) { uomRepository.getUnitOfMeasure(uomId).blockingGet() }
        assertStatusError(404) { uomRepository.deleteUnitOfMeasure(uomId).blockingGet() }

        val product = productRepository
            .createProduct("DP-$suffix", "Delete Product $suffix", "STOCK", TestDatabase.SEED_UOM_UNIT, true, JsonObject())
            .blockingGet()
        val productId = product.getString("productId")
        productRepository.deleteProduct(productId).blockingGet()
        assertStatusError(404) { productRepository.getProduct(productId, false).blockingGet() }
        assertStatusError(404) { productRepository.deleteProduct(productId).blockingGet() }

        val variantProduct = productRepository
            .createProduct("DVP-$suffix", "Delete Variant Product $suffix", "STOCK", TestDatabase.SEED_UOM_UNIT, true, JsonObject())
            .blockingGet()
        val variantProductId = variantProduct.getString("productId")
        val variant = variantRepository
            .createProductVariant(variantProductId, "DV-$suffix", "Delete Variant $suffix", true, JsonObject())
            .blockingGet()
        val variantId = variant.getString("variantId")
        variantRepository.deleteProductVariant(variantProductId, variantId).blockingGet()
        assertStatusError(404) { variantRepository.getProductVariant(variantProductId, variantId).blockingGet() }
        assertStatusError(404) { variantRepository.deleteProductVariant(variantProductId, variantId).blockingGet() }
        productRepository.deleteProduct(variantProductId).blockingGet()

        val location = locationRepository
            .createLocation("DL-$suffix", "Delete Location $suffix", "WAREHOUSE", true, JsonObject())
            .blockingGet()
        val locationId = location.getString("locationId")
        locationRepository.deleteLocation(locationId).blockingGet()
        assertStatusError(404) { locationRepository.getLocation(locationId).blockingGet() }
        assertStatusError(404) { locationRepository.deleteLocation(locationId).blockingGet() }
    }

    @Test
    fun nullableJsonColumnsMapToEmptyObjects() {
        val suffix = suffix()

        val product = productRepository
            .createProduct("NPJ-$suffix", "Null Product JSON $suffix", "STOCK", TestDatabase.SEED_UOM_UNIT, true, null)
            .blockingGet()
        val productId = product.getString("productId")
        val fetchedProduct = productRepository.getProduct(productId, false).blockingGet()
        assertEquals(JsonObject(), fetchedProduct.getJsonObject("metadata"))

        val variant = variantRepository
            .createProductVariant(productId, "NVJ-$suffix", "Null Variant JSON $suffix", true, null)
            .blockingGet()
        val fetchedVariant = variantRepository.getProductVariant(productId, variant.getString("variantId")).blockingGet()
        assertEquals(JsonObject(), fetchedVariant.getJsonObject("attributes"))

        val location = locationRepository
            .createLocation("NL-$suffix", "Null Location JSON $suffix", "WAREHOUSE", true, null)
            .blockingGet()
        val fetchedLocation = locationRepository.getLocation(location.getString("locationId")).blockingGet()
        assertEquals(JsonObject(), fetchedLocation.getJsonObject("address"))

        variantRepository.deleteProductVariant(productId, variant.getString("variantId")).blockingGet()
        productRepository.deleteProduct(productId).blockingGet()
        locationRepository.deleteLocation(location.getString("locationId")).blockingGet()
    }

    @Test
    fun listQueriesApplyFiltersFallbackSortAndPagination() {
        val suffix = suffix()
        val productIds = mutableListOf<String>()
        val variantIds = mutableListOf<Pair<String, String>>()
        val locationIds = mutableListOf<String>()

        try {
            val productA = productRepository
                .createProduct("LQP-$suffix-A", "List Product A $suffix", "STOCK", TestDatabase.SEED_UOM_UNIT, true, JsonObject())
                .blockingGet()
            val productB = productRepository
                .createProduct("LQP-$suffix-B", "List Product B $suffix", "STOCK", TestDatabase.SEED_UOM_UNIT, true, JsonObject())
                .blockingGet()
            productIds += productA.getString("productId")
            productIds += productB.getString("productId")

            val variantA = variantRepository
                .createProductVariant(productA.getString("productId"), "LQV-$suffix-A", "List Variant A $suffix", true, JsonObject())
                .blockingGet()
            val variantB = variantRepository
                .createProductVariant(productA.getString("productId"), "LQV-$suffix-B", "List Variant B $suffix", true, JsonObject())
                .blockingGet()
            variantIds += productA.getString("productId") to variantA.getString("variantId")
            variantIds += productA.getString("productId") to variantB.getString("variantId")

            val activeLocation = locationRepository
                .createLocation("LQL-$suffix-A", "List Location A $suffix", "WAREHOUSE", true, JsonObject())
                .blockingGet()
            val inactiveLocation = locationRepository
                .createLocation("LQL-$suffix-B", "List Location B $suffix", "WAREHOUSE", false, JsonObject())
                .blockingGet()
            locationIds += activeLocation.getString("locationId")
            locationIds += inactiveLocation.getString("locationId")

            val productsPage = productRepository
                .listProducts(0, 1, "sku,desc", "LQP-$suffix", "STOCK", true)
                .blockingGet()
            assertPagination(productsPage, page = 0, size = 1, totalElements = 2, totalPages = 2)
            assertEquals(1, fieldValues(productsPage, "sku").size)

            val productsWithFallbackSort = productRepository
                .listProducts(0, 10, "unknown,sideways", "LQP-$suffix", "STOCK", true)
                .blockingGet()
            assertEquals(listOf("LQP-$suffix-A", "LQP-$suffix-B"), fieldValues(productsWithFallbackSort, "sku").sorted())

            val variantsPage = variantRepository
                .listProductVariants(productA.getString("productId"), 0, 1, "sku,desc", true)
                .blockingGet()
            assertPagination(variantsPage, page = 0, size = 1, totalElements = 2, totalPages = 2)

            val activeLocations = locationRepository
                .listLocations(0, 10, "unknown,sideways", "LQL-$suffix", null, "WAREHOUSE", true)
                .blockingGet()
            assertEquals(listOf("LQL-$suffix-A"), fieldValues(activeLocations, "code"))

            val allLocations = locationRepository
                .listLocations(0, 10, "code,asc", "LQL-$suffix", null, "WAREHOUSE", false)
                .blockingGet()
            assertEquals(listOf("LQL-$suffix-A", "LQL-$suffix-B"), fieldValues(allLocations, "code").sorted())
        } finally {
            variantIds.forEach { (productId, variantId) -> deleteProductVariantIfPresent(productId, variantId) }
            productIds.forEach { productId -> deleteProductIfPresent(productId) }
            locationIds.forEach { locationId -> deleteLocationIfPresent(locationId) }
        }
    }

    private fun assertPagination(response: JsonObject, page: Int, size: Int, totalElements: Int, totalPages: Int) {
        val pagination = response.getJsonObject("pagination")
        assertEquals(page, pagination.getInteger("page"))
        assertEquals(size, pagination.getInteger("size"))
        assertEquals(totalElements, pagination.getInteger("totalElements"))
        assertEquals(totalPages, pagination.getInteger("totalPages"))
    }

    private fun fieldValues(response: JsonObject, fieldName: String): List<String> {
        return response.getJsonArray("data")
            .filterIsInstance<JsonObject>()
            .map { it.getString(fieldName) }
    }

    private fun deleteProductVariantIfPresent(productId: String, variantId: String) {
        try {
            variantRepository.deleteProductVariant(productId, variantId).blockingGet()
        } catch (_: Throwable) {
        }
    }

    private fun deleteProductIfPresent(productId: String) {
        try {
            productRepository.deleteProduct(productId).blockingGet()
        } catch (_: Throwable) {
        }
    }

    private fun deleteLocationIfPresent(locationId: String) {
        try {
            locationRepository.deleteLocation(locationId).blockingGet()
        } catch (_: Throwable) {
        }
    }

    private fun assertStatusError(status: Int, action: () -> Unit) {
        try {
            action()
            fail("Expected status $status error")
        } catch (error: Throwable) {
            val expected = ErrorCodes.fromStatus(status)
            val message = generateSequence(error) { it.cause }
                .mapNotNull { it.message }
                .joinToString(" | ")
            assertTrue(message.contains(expected), "Expected error message to contain $expected but was: $message")
        }
    }

    private fun suffix(): String = UUID.randomUUID().toString().replace("-", "").take(8).uppercase()
}
