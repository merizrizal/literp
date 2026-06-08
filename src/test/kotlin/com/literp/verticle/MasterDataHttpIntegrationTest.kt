package com.literp.verticle

import com.literp.common.ErrorCodes
import com.literp.repository.LocationRepository
import com.literp.repository.ProductRepository
import com.literp.repository.ProductVariantRepository
import com.literp.repository.UnitOfMeasureRepository
import com.literp.service.master.impl.LocationServiceImpl
import com.literp.service.master.impl.ProductServiceImpl
import com.literp.service.master.impl.ProductVariantServiceImpl
import com.literp.service.master.impl.UnitOfMeasureServiceImpl
import com.literp.test.TestDatabase
import com.literp.verticle.handler.LocationHandler
import com.literp.verticle.handler.ProductHandler
import com.literp.verticle.handler.UnitOfMeasureHandler
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.handler.HttpException
import io.vertx.rxjava3.core.http.HttpServer
import io.vertx.rxjava3.ext.web.Router
import io.vertx.rxjava3.ext.web.openapi.router.RouterBuilder
import io.vertx.rxjava3.openapi.contract.OpenAPIContract
import io.vertx.rxjava3.sqlclient.Pool
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import io.vertx.rxjava3.core.Vertx as RxVertx

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MasterDataHttpIntegrationTest {
    private lateinit var coreVertx: Vertx
    private lateinit var rxVertx: RxVertx
    private lateinit var pool: Pool
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private val client = HttpClient.newHttpClient()

    @BeforeAll
    fun setUp() {
        coreVertx = Vertx.vertx()
        rxVertx = RxVertx.newInstance(coreVertx)
        pool = TestDatabase.createPool(rxVertx)
        TestDatabase.assumeAvailable(pool)

        server = rxVertx.createHttpServer()
            .requestHandler(createRouter())
            .rxListen(0, "127.0.0.1")
            .blockingGet()
        baseUrl = "http://127.0.0.1:${server.actualPort()}/api/v1"
    }

    @AfterAll
    fun tearDown() {
        if (::server.isInitialized) {
            server.rxClose().blockingAwait()
        }
        if (::pool.isInitialized) {
            pool.rxClose().blockingAwait()
        }
        if (::coreVertx.isInitialized) {
            coreVertx.close().toCompletionStage().toCompletableFuture().get()
        }
    }

    @Test
    fun masterDataEndpointsCoverHappyPaths() {
        val suffix = suffix()

        val uom = expect(
            "POST",
            "/uom",
            201,
            JsonObject()
                .put("code", "H$suffix")
                .put("name", "HTTP UOM $suffix")
                .put("baseUnit", "unit")
        ).json!!.getJsonObject("data")
        val uomId = uom.getString("uomId")
        expect("GET", "/uom?page=0&size=10&sort=code,asc", 200)
        expect("GET", "/uom/$uomId", 200)
        expect("PUT", "/uom/$uomId", 200, JsonObject().put("name", "HTTP UOM Updated $suffix").put("baseUnit", "unit"))
        expect("DELETE", "/uom/$uomId", 204)

        val product = expect(
            "POST",
            "/products",
            201,
            JsonObject()
                .put("sku", "HP-$suffix")
                .put("name", "HTTP Product $suffix")
                .put("productType", "STOCK")
                .put("baseUom", TestDatabase.SEED_UOM_UNIT)
                .put("metadata", JsonObject().put("testRun", suffix))
        ).json!!.getJsonObject("data")
        val productId = product.getString("productId")
        expect("GET", "/products?page=0&size=10&sort=sku,asc&sku=HP-$suffix&productType=STOCK&activeOnly=true", 200)
        expect("GET", "/products/$productId?includeVariants=true", 200)
        expect(
            "PUT",
            "/products/$productId",
            200,
            JsonObject()
                .put("name", "HTTP Product Updated $suffix")
                .put("productType", "STOCK")
                .put("active", true)
                .put("metadata", JsonObject().put("updated", true))
        )

        val variant = expect(
            "POST",
            "/products/$productId/variants",
            201,
            JsonObject()
                .put("sku", "HV-$suffix")
                .put("name", "HTTP Variant $suffix")
                .put("attributes", JsonObject().put("testRun", suffix))
        ).json!!.getJsonObject("data")
        val variantId = variant.getString("variantId")
        expect("GET", "/products/$productId/variants?page=0&size=10&sort=sku,asc&activeOnly=true", 200)
        expect("GET", "/products/$productId/variants/$variantId", 200)
        expect(
            "PUT",
            "/products/$productId/variants/$variantId",
            200,
            JsonObject()
                .put("name", "HTTP Variant Updated $suffix")
                .put("active", true)
                .put("attributes", JsonObject().put("updated", true))
        )
        expect("DELETE", "/products/$productId/variants/$variantId", 204)
        expect("DELETE", "/products/$productId", 204)

        val location = expect(
            "POST",
            "/locations",
            201,
            JsonObject()
                .put("code", "HL-$suffix")
                .put("name", "HTTP Location $suffix")
                .put("locationType", "WAREHOUSE")
                .put("isActive", false)
                .put("address", JsonObject().put("testRun", suffix))
        ).json!!.getJsonObject("data")
        val locationId = location.getString("locationId")
        val locationCode = location.getString("code")
        expect("GET", "/locations?page=0&size=10&sort=code,asc&code=$locationCode&activeOnly=false", 200)
        expect("GET", "/locations/$locationId", 200)
        expect("GET", "/locations/by-code/$locationCode", 200)
        expect(
            "PUT",
            "/locations/$locationId",
            200,
            JsonObject()
                .put("name", "HTTP Location Updated $suffix")
                .put("locationType", "WAREHOUSE")
                .put("isActive", true)
                .put("address", JsonObject().put("updated", true))
        )
        expect("DELETE", "/locations/$locationId", 204)
    }

    @Test
    fun masterDataEndpointsCoverErrorsAndListValidation() {
        val suffix = suffix()
        val missingId = UUID.randomUUID().toString()

        expect("GET", "/uom/$missingId", 404)
        expect("PUT", "/uom/$missingId", 404, JsonObject().put("name", "Missing UOM"))
        expect("DELETE", "/uom/$missingId", 404)

        val uomCode = "E$suffix"
        expect("POST", "/uom", 201, JsonObject().put("code", uomCode).put("name", "Error UOM $suffix"))
        expect("POST", "/uom", 409, JsonObject().put("code", uomCode).put("name", "Error UOM duplicate $suffix"))

        expect("GET", "/products/$missingId", 404)
        expect(
            "PUT",
            "/products/$missingId",
            404,
            JsonObject().put("name", "Missing Product").put("productType", "STOCK")
        )
        expect("DELETE", "/products/$missingId", 404)
        expect("GET", "/products/$missingId/variants", 404)
        expect(
            "POST",
            "/products/$missingId/variants",
            404,
            JsonObject().put("sku", "EV-$suffix").put("name", "Missing Product Variant")
        )
        expect("GET", "/products/$missingId/variants/$missingId", 404)
        expect(
            "PUT",
            "/products/$missingId/variants/$missingId",
            404,
            JsonObject().put("name", "Missing Variant")
        )
        expect("DELETE", "/products/$missingId/variants/$missingId", 404)

        expect("GET", "/locations/$missingId", 404)
        expect("GET", "/locations/by-code/MISSING-$suffix", 404)
        expect(
            "PUT",
            "/locations/$missingId",
            404,
            JsonObject().put("name", "Missing Location").put("locationType", "WAREHOUSE")
        )
        expect("DELETE", "/locations/$missingId", 404)

        expect("GET", "/uom?page=-1", 400)
        expect("GET", "/uom?size=0", 400)
        expect("GET", "/uom?size=101", 400)
        expect("GET", "/uom?sort=code", 400)
        expect("GET", "/uom?sort=unknown,asc", 400)
        expect("GET", "/uom?sort=code,sideways", 400)
        expect("GET", "/products?activeOnly=maybe", 400)
        expect("GET", "/products/$missingId?includeVariants=maybe", 400)
        expect("GET", "/locations?activeOnly=maybe", 400)

        val createdUom = expect("GET", "/uom?sort=code,asc&size=100", 200).json!!
            .getJsonArray("data")
            .filterIsInstance<JsonObject>()
            .first { it.getString("code") == uomCode }
        expect("DELETE", "/uom/${createdUom.getString("uomId")}", 204)
    }

    private fun createRouter(): Router {
        val uomRepository = UnitOfMeasureRepository(pool)
        val productRepository = ProductRepository(pool)
        val variantRepository = ProductVariantRepository(pool)
        val locationRepository = LocationRepository(pool)

        val uomHandler = UnitOfMeasureHandler(UnitOfMeasureServiceImpl(uomRepository))
        val productHandler = ProductHandler(ProductServiceImpl(productRepository), ProductVariantServiceImpl(variantRepository))
        val locationHandler = LocationHandler(LocationServiceImpl(locationRepository))

        val productContract = OpenAPIContract.rxFrom(rxVertx, "api_collections/open_api_spec/product-catalog.yaml").blockingGet()
        val locationContract = OpenAPIContract.rxFrom(rxVertx, "api_collections/open_api_spec/locations.yaml").blockingGet()
        val productRouterBuilder = RouterBuilder.create(rxVertx, productContract)
        val locationRouterBuilder = RouterBuilder.create(rxVertx, locationContract)

        productRouterBuilder.getRoute("listUnitOfMeasures").addHandler(uomHandler::listUnitOfMeasures)
        productRouterBuilder.getRoute("createUnitOfMeasure").addHandler(uomHandler::createUnitOfMeasure)
        productRouterBuilder.getRoute("getUnitOfMeasure").addHandler(uomHandler::getUnitOfMeasure)
        productRouterBuilder.getRoute("updateUnitOfMeasure").addHandler(uomHandler::updateUnitOfMeasure)
        productRouterBuilder.getRoute("deleteUnitOfMeasure").addHandler(uomHandler::deleteUnitOfMeasure)

        productRouterBuilder.getRoute("listProducts").addHandler(productHandler::listProducts)
        productRouterBuilder.getRoute("createProduct").addHandler(productHandler::createProduct)
        productRouterBuilder.getRoute("getProduct").addHandler(productHandler::getProduct)
        productRouterBuilder.getRoute("updateProduct").addHandler(productHandler::updateProduct)
        productRouterBuilder.getRoute("deleteProduct").addHandler(productHandler::deleteProduct)
        productRouterBuilder.getRoute("listProductVariants").addHandler(productHandler::listProductVariants)
        productRouterBuilder.getRoute("createProductVariant").addHandler(productHandler::createProductVariant)
        productRouterBuilder.getRoute("getProductVariant").addHandler(productHandler::getProductVariant)
        productRouterBuilder.getRoute("updateProductVariant").addHandler(productHandler::updateProductVariant)
        productRouterBuilder.getRoute("deleteProductVariant").addHandler(productHandler::deleteProductVariant)

        locationRouterBuilder.getRoute("listLocations").addHandler(locationHandler::listLocations)
        locationRouterBuilder.getRoute("createLocation").addHandler(locationHandler::createLocation)
        locationRouterBuilder.getRoute("getLocation").addHandler(locationHandler::getLocation)
        locationRouterBuilder.getRoute("getLocationByCode").addHandler(locationHandler::getLocationByCode)
        locationRouterBuilder.getRoute("updateLocation").addHandler(locationHandler::updateLocation)
        locationRouterBuilder.getRoute("deleteLocation").addHandler(locationHandler::deleteLocation)

        return Router.router(rxVertx).apply {
            route().failureHandler { context ->
                val failure = context.failure()
                val statusCode = when {
                    failure is HttpException -> failure.statusCode
                    context.statusCode() > 0 -> context.statusCode()
                    else -> 500
                }
                context.response()
                    .setStatusCode(statusCode)
                    .putHeader("Content-Type", "application/json")
                    .end(
                        JsonObject()
                            .put("error", failure?.message ?: "Bad request")
                            .put("errorCode", ErrorCodes.fromStatus(statusCode))
                            .put("status", statusCode)
                            .put("errorId", UUID.randomUUID().toString())
                            .encode()
                    )
            }
            route("/api/v1/*").subRouter(productRouterBuilder.createRouter())
            route("/api/v1/*").subRouter(locationRouterBuilder.createRouter())
        }
    }

    private fun expect(method: String, path: String, status: Int, body: JsonObject? = null): HttpResult {
        val result = request(method, path, body)
        assertEquals(status, result.status, "Unexpected status for $method $path with body ${result.rawBody}")
        if (status != 204) {
            assertNotNull(result.json, "Expected JSON body for $method $path")
        }
        val json = result.json
        if (status >= 400 && json != null) {
            assertErrorEnvelope(json, status)
        }
        if (status == 200 && json?.containsKey("pagination") == true) {
            assertListEnvelope(json)
        }
        return result
    }

    private fun assertListEnvelope(json: JsonObject) {
        assertNotNull(json.getJsonArray("data"), "Expected top-level data array")
        val pagination = json.getJsonObject("pagination")
        assertNotNull(pagination, "Expected top-level pagination object")
        assertTrue(pagination.containsKey("page"), "Expected pagination.page")
        assertTrue(pagination.containsKey("size"), "Expected pagination.size")
        assertTrue(pagination.containsKey("totalElements"), "Expected pagination.totalElements")
        assertTrue(pagination.containsKey("totalPages"), "Expected pagination.totalPages")
        assertFalse(pagination.containsKey("total"), "Unexpected pagination.total")
        assertFalse(pagination.containsKey("hasNext"), "Unexpected pagination.hasNext")
        assertFalse(pagination.containsKey("hasPrevious"), "Unexpected pagination.hasPrevious")
    }

    private fun assertErrorEnvelope(json: JsonObject, status: Int) {
        assertTrue(!json.getString("error").isNullOrBlank(), "Expected error message")
        assertTrue(!json.getString("errorCode").isNullOrBlank(), "Expected errorCode")
        assertEquals(status, json.getInteger("status"), "Expected error status")
        assertTrue(!json.getString("errorId").isNullOrBlank(), "Expected errorId")
        assertFalse(json.containsKey("code"), "Unexpected legacy error code field")
        assertFalse(json.containsKey("message"), "Unexpected legacy error message field")
    }

    private fun request(method: String, path: String, body: JsonObject?): HttpResult {
        val builder = HttpRequest.newBuilder(URI.create("$baseUrl$path"))
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
        } else {
            builder.header("Content-Type", "application/json")
            builder.method(method, HttpRequest.BodyPublishers.ofString(body.encode()))
        }

        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        val rawBody = response.body()
        val json = rawBody.takeIf { it.isNotBlank() }?.let { JsonObject(it) }
        return HttpResult(response.statusCode(), rawBody, json)
    }

    private fun suffix(): String = UUID.randomUUID().toString().replace("-", "").take(8).uppercase()

    private data class HttpResult(val status: Int, val rawBody: String, val json: JsonObject?)
}
