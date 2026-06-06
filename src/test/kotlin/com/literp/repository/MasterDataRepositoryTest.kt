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
