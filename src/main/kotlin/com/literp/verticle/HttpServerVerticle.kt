package com.literp.verticle

import com.literp.config.Config
import com.literp.db.DatabaseConnection
import com.literp.repository.LocationRepository
import com.literp.repository.ProductRepository
import com.literp.repository.ProductVariantRepository
import com.literp.repository.UnitOfMeasureRepository
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.observers.DisposableSingleObserver
import io.vertx.core.Promise
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.internal.logging.LoggerFactory
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.rxjava3.core.Vertx
import io.vertx.rxjava3.ext.web.Router
import io.vertx.rxjava3.ext.web.RoutingContext
import io.vertx.rxjava3.ext.web.handler.HSTSHandler
import io.vertx.rxjava3.ext.web.openapi.router.RouterBuilder
import io.vertx.rxjava3.openapi.contract.OpenAPIContract

class HttpServerVerticle(
    private val vertx: Vertx
) : CoroutineVerticle() {
    private val logger = LoggerFactory.getLogger(this@HttpServerVerticle.javaClass)

    private lateinit var uomRepository: UnitOfMeasureRepository
    private lateinit var productRepository: ProductRepository
    private lateinit var variantRepository: ProductVariantRepository
    private lateinit var locationRepository: LocationRepository

    override fun start(startFuture: Promise<Void>?) {
        val pool = DatabaseConnection.createPool(vertx)
        uomRepository = UnitOfMeasureRepository(pool)
        productRepository = ProductRepository(pool)
        variantRepository = ProductVariantRepository(pool)
        locationRepository = LocationRepository(pool)

        // Load both OpenAPI specs
        loadProductCatalogAndLocations(startFuture)
    }

    private fun loadProductCatalogAndLocations(startFuture: Promise<Void>?) {
        OpenAPIContract
            .rxFrom(vertx, "api_collections/open_api_spec/product-catalog.yaml")
            .flatMap { productContract ->
                OpenAPIContract
                    .rxFrom(vertx, "api_collections/open_api_spec/locations.yaml")
                    .map { locationContract -> Pair(productContract, locationContract) }
            }
            .flatMap { (productContract, locationContract) ->
                Single.just(Pair(
                    RouterBuilder.create(vertx, productContract),
                    RouterBuilder.create(vertx, locationContract)
                ))
            }
            .subscribeWith(object : DisposableSingleObserver<Pair<RouterBuilder, RouterBuilder>>() {
                override fun onSuccess(routers: Pair<RouterBuilder, RouterBuilder>) {
                    logger.info("Deployed OpenAPI Contracts")

                    val (productRouterBuilder, locationRouterBuilder) = routers

                    // Register Product Catalog handlers
                    registerProductCatalogHandlers(productRouterBuilder)
                    // Register Location handlers
                    registerLocationHandlers(locationRouterBuilder)

                    val productRouter = productRouterBuilder.createRouter()
                    val locationRouter = locationRouterBuilder.createRouter()

                    val router = Router.router(vertx).apply {
                        route("/api/v1/*").subRouter(productRouter)
                        route("/api/v1/*").subRouter(locationRouter)
                        get("/").handler(this@HttpServerVerticle::getIndex)
                        route().handler(HSTSHandler.create())
                    }

                    val config = Config()

                    val httpOptions = HttpServerOptions()
                        .setPort(config.httpPort)

                    vertx.createHttpServer(httpOptions)
                        .requestHandler(router)
                        .rxListen()
                        .subscribe(
                            {
                                logger.info("Deployed HttpServerVerticle: listening at http://localhost:${config.httpPort}")
                                startFuture?.complete()
                            },
                            { failure ->
                                logger.error("Fail to deploy HttpServerVerticle: ${failure.message}")
                                startFuture?.fail(failure.cause)
                            }
                        )
                }

                override fun onError(e: Throwable) {
                    logger.error("Fail to deploy OpenAPI Contracts: ${e.message}")
                    startFuture?.fail(e.cause)
                }
            })
    }

    private fun registerProductCatalogHandlers(routerBuilder: RouterBuilder) {
        // Unit of Measure handlers
        routerBuilder.getRoute("listUnitOfMeasures").addHandler(this::listUnitOfMeasures)
        routerBuilder.getRoute("createUnitOfMeasure").addHandler(this::createUnitOfMeasure)
        routerBuilder.getRoute("getUnitOfMeasure").addHandler(this::getUnitOfMeasure)
        routerBuilder.getRoute("updateUnitOfMeasure").addHandler(this::updateUnitOfMeasure)
        routerBuilder.getRoute("deleteUnitOfMeasure").addHandler(this::deleteUnitOfMeasure)

        // Product handlers
        routerBuilder.getRoute("listProducts").addHandler(this::listProducts)
        routerBuilder.getRoute("createProduct").addHandler(this::createProduct)
        routerBuilder.getRoute("getProduct").addHandler(this::getProduct)
        routerBuilder.getRoute("updateProduct").addHandler(this::updateProduct)
        routerBuilder.getRoute("deleteProduct").addHandler(this::deleteProduct)

        // Product Variant handlers
        routerBuilder.getRoute("listProductVariants").addHandler(this::listProductVariants)
        routerBuilder.getRoute("createProductVariant").addHandler(this::createProductVariant)
        routerBuilder.getRoute("getProductVariant").addHandler(this::getProductVariant)
        routerBuilder.getRoute("updateProductVariant").addHandler(this::updateProductVariant)
        routerBuilder.getRoute("deleteProductVariant").addHandler(this::deleteProductVariant)
    }

    private fun registerLocationHandlers(routerBuilder: RouterBuilder) {
        routerBuilder.getRoute("listLocations").addHandler(this::listLocations)
        routerBuilder.getRoute("createLocation").addHandler(this::createLocation)
        routerBuilder.getRoute("getLocation").addHandler(this::getLocation)
        routerBuilder.getRoute("getLocationByCode").addHandler(this::getLocationByCode)
        routerBuilder.getRoute("updateLocation").addHandler(this::updateLocation)
        routerBuilder.getRoute("deleteLocation").addHandler(this::deleteLocation)
    }

    // ==================== UNIT OF MEASURE HANDLERS ====================

    private fun listUnitOfMeasures(context: RoutingContext) {
        val page = context.queryParam("page").firstOrNull()?.toIntOrNull() ?: 0
        val size = context.queryParam("size").firstOrNull()?.toIntOrNull() ?: 20
        val sort = context.queryParam("sort").firstOrNull() ?: "code,asc"

        uomRepository.listUnitOfMeasures(page, size, sort)
            .subscribe(
                { result -> putResponse(context, 200, result) },
                { error -> putErrorResponse(context, 500, "Failed to list UOM: ${error.message}") }
            )
    }

    private fun createUnitOfMeasure(context: RoutingContext) {
        val body = context.body().asJsonObject()
        val code = body.getString("code")
        val name = body.getString("name")
        val baseUnit = body.getString("baseUnit")

        if (code.isNullOrEmpty() || name.isNullOrEmpty()) {
            putErrorResponse(context, 400, "Code and name are required")
            return
        }

        uomRepository.checkCodeExists(code)
            .flatMap { exists ->
                if (exists) {
                    Single.error(Exception("UOM code already exists"))
                } else {
                    uomRepository.createUnitOfMeasure(code, name, baseUnit)
                }
            }
            .subscribe(
                { result -> putResponse(context, 201, JsonObject().put("data", result)) },
                { error ->
                    if (error.message?.contains("already exists") == true) {
                        putErrorResponse(context, 409, error.message ?: "Conflict")
                    } else {
                        putErrorResponse(context, 500, "Failed to create UOM: ${error.message}")
                    }
                }
            )
    }

    private fun getUnitOfMeasure(context: RoutingContext) {
        val uomId = context.pathParam("uomId")

        uomRepository.getUnitOfMeasure(uomId)
            .subscribe(
                { result -> putResponse(context, 200, JsonObject().put("data", result)) },
                { error -> putErrorResponse(context, 404, "Unit of measure not found") }
            )
    }

    private fun updateUnitOfMeasure(context: RoutingContext) {
        val uomId = context.pathParam("uomId")
        val body = context.body().asJsonObject()
        val name = body.getString("name")
        val baseUnit = body.getString("baseUnit")

        if (name.isNullOrEmpty()) {
            putErrorResponse(context, 400, "Name is required")
            return
        }

        uomRepository.updateUnitOfMeasure(uomId, name, baseUnit)
            .subscribe(
                { result -> putResponse(context, 200, JsonObject().put("data", result)) },
                { error -> putErrorResponse(context, 404, "Unit of measure not found") }
            )
    }

    private fun deleteUnitOfMeasure(context: RoutingContext) {
        val uomId = context.pathParam("uomId")

        uomRepository.deleteUnitOfMeasure(uomId)
            .subscribe(
                { putResponse(context, 204, JsonObject()) },
                { error -> putErrorResponse(context, 500, "Failed to delete UOM: ${error.message}") }
            )
    }

    // ==================== PRODUCT HANDLERS ====================

    private fun listProducts(context: RoutingContext) {
        val page = context.queryParam("page").firstOrNull()?.toIntOrNull() ?: 0
        val size = context.queryParam("size").firstOrNull()?.toIntOrNull() ?: 20
        val sort = context.queryParam("sort").firstOrNull() ?: "sku,asc"

        productRepository.listProducts(page, size, sort)
            .subscribe(
                { result -> putResponse(context, 200, result) },
                { error -> putErrorResponse(context, 500, "Failed to list products: ${error.message}") }
            )
    }

    private fun createProduct(context: RoutingContext) {
        val body = context.body().asJsonObject()
        val sku = body.getString("sku")
        val name = body.getString("name")
        val productType = body.getString("productType")
        val baseUom = body.getString("baseUom")
        val metadata = body.getJsonObject("metadata")

        if (sku.isNullOrEmpty() || name.isNullOrEmpty() || productType.isNullOrEmpty() || baseUom.isNullOrEmpty()) {
            putErrorResponse(context, 400, "sku, name, productType, and baseUom are required")
            return
        }

        productRepository.checkSkuExists(sku)
            .flatMap { exists ->
                if (exists) {
                    Single.error(Exception("Product SKU already exists"))
                } else {
                    productRepository.createProduct(sku, name, productType, baseUom, metadata)
                }
            }
            .subscribe(
                { result -> putResponse(context, 201, JsonObject().put("data", result)) },
                { error ->
                    if (error.message?.contains("already exists") == true) {
                        putErrorResponse(context, 409, error.message ?: "Conflict")
                    } else {
                        putErrorResponse(context, 500, "Failed to create product: ${error.message}")
                    }
                }
            )
    }

    private fun getProduct(context: RoutingContext) {
        val productId = context.pathParam("productId")

        productRepository.getProduct(productId)
            .subscribe(
                { result -> putResponse(context, 200, JsonObject().put("data", result)) },
                { error -> putErrorResponse(context, 404, "Product not found") }
            )
    }

    private fun updateProduct(context: RoutingContext) {
        val productId = context.pathParam("productId")
        val body = context.body().asJsonObject()
        val name = body.getString("name")
        val productType = body.getString("productType")
        val metadata = body.getJsonObject("metadata")

        if (name.isNullOrEmpty() || productType.isNullOrEmpty()) {
            putErrorResponse(context, 400, "name and productType are required")
            return
        }

        productRepository.updateProduct(productId, name, productType, metadata)
            .subscribe(
                { result -> putResponse(context, 200, JsonObject().put("data", result)) },
                { error -> putErrorResponse(context, 404, "Product not found") }
            )
    }

    private fun deleteProduct(context: RoutingContext) {
        val productId = context.pathParam("productId")

        productRepository.deleteProduct(productId)
            .subscribe(
                { putResponse(context, 204, JsonObject()) },
                { error -> putErrorResponse(context, 500, "Failed to delete product: ${error.message}") }
            )
    }

    // ==================== PRODUCT VARIANT HANDLERS ====================

    private fun listProductVariants(context: RoutingContext) {
        val productId = context.pathParam("productId")
        val page = context.queryParam("page").firstOrNull()?.toIntOrNull() ?: 0
        val size = context.queryParam("size").firstOrNull()?.toIntOrNull() ?: 20
        val sort = context.queryParam("sort").firstOrNull() ?: "sku,asc"

        variantRepository.listProductVariants(productId, page, size, sort)
            .subscribe(
                { result -> putResponse(context, 200, result) },
                { error -> putErrorResponse(context, 500, "Failed to list variants: ${error.message}") }
            )
    }

    private fun createProductVariant(context: RoutingContext) {
        val productId = context.pathParam("productId")
        val body = context.body().asJsonObject()
        val sku = body.getString("sku")
        val name = body.getString("name")
        val attributes = body.getJsonObject("attributes")

        if (sku.isNullOrEmpty() || name.isNullOrEmpty()) {
            putErrorResponse(context, 400, "sku and name are required")
            return
        }

        variantRepository.checkSkuExists(sku)
            .flatMap { exists ->
                if (exists) {
                    Single.error(Exception("Variant SKU already exists"))
                } else {
                    variantRepository.createProductVariant(productId, sku, name, attributes)
                }
            }
            .subscribe(
                { result -> putResponse(context, 201, JsonObject().put("data", result)) },
                { error ->
                    if (error.message?.contains("already exists") == true) {
                        putErrorResponse(context, 409, error.message ?: "Conflict")
                    } else {
                        putErrorResponse(context, 500, "Failed to create variant: ${error.message}")
                    }
                }
            )
    }

    private fun getProductVariant(context: RoutingContext) {
        val productId = context.pathParam("productId")
        val variantId = context.pathParam("variantId")

        variantRepository.getProductVariant(productId, variantId)
            .subscribe(
                { result -> putResponse(context, 200, JsonObject().put("data", result)) },
                { error -> putErrorResponse(context, 404, "Product variant not found") }
            )
    }

    private fun updateProductVariant(context: RoutingContext) {
        val variantId = context.pathParam("variantId")
        val body = context.body().asJsonObject()
        val name = body.getString("name")
        val attributes = body.getJsonObject("attributes")

        if (name.isNullOrEmpty()) {
            putErrorResponse(context, 400, "name is required")
            return
        }

        variantRepository.updateProductVariant(variantId, name, attributes)
            .subscribe(
                { result -> putResponse(context, 200, JsonObject().put("data", result)) },
                { error -> putErrorResponse(context, 404, "Product variant not found") }
            )
    }

    private fun deleteProductVariant(context: RoutingContext) {
        val variantId = context.pathParam("variantId")

        variantRepository.deleteProductVariant(variantId)
            .subscribe(
                { putResponse(context, 204, JsonObject()) },
                { error -> putErrorResponse(context, 500, "Failed to delete variant: ${error.message}") }
            )
    }

    // ==================== LOCATION HANDLERS ====================

    private fun listLocations(context: RoutingContext) {
        val page = context.queryParam("page").firstOrNull()?.toIntOrNull() ?: 0
        val size = context.queryParam("size").firstOrNull()?.toIntOrNull() ?: 20
        val sort = context.queryParam("sort").firstOrNull() ?: "code,asc"
        val code = context.queryParam("code").firstOrNull()
        val name = context.queryParam("name").firstOrNull()
        val locationType = context.queryParam("locationType").firstOrNull()
        val activeOnly = context.queryParam("activeOnly").firstOrNull()?.toBoolean() ?: true

        locationRepository.listLocations(page, size, sort, code, name, locationType, activeOnly)
            .subscribe(
                { result -> putResponse(context, 200, result) },
                { error -> putErrorResponse(context, 500, "Failed to list locations: ${error.message}") }
            )
    }

    private fun createLocation(context: RoutingContext) {
        val body = context.body().asJsonObject()
        val code = body.getString("code")
        val name = body.getString("name")
        val locationType = body.getString("locationType")
        val address = body.getJsonObject("address")

        if (code.isNullOrEmpty() || name.isNullOrEmpty() || locationType.isNullOrEmpty()) {
            putErrorResponse(context, 400, "code, name, and locationType are required")
            return
        }

        locationRepository.checkCodeExists(code)
            .flatMap { exists ->
                if (exists) {
                    Single.error(Exception("Location code already exists"))
                } else {
                    locationRepository.createLocation(code, name, locationType, address)
                }
            }
            .subscribe(
                { result -> putResponse(context, 201, JsonObject().put("data", result)) },
                { error ->
                    if (error.message?.contains("already exists") == true) {
                        putErrorResponse(context, 409, error.message ?: "Conflict")
                    } else {
                        putErrorResponse(context, 500, "Failed to create location: ${error.message}")
                    }
                }
            )
    }

    private fun getLocation(context: RoutingContext) {
        val locationId = context.pathParam("locationId")

        locationRepository.getLocation(locationId)
            .subscribe(
                { result -> putResponse(context, 200, JsonObject().put("data", result)) },
                { error -> putErrorResponse(context, 404, "Location not found") }
            )
    }

    private fun getLocationByCode(context: RoutingContext) {
        val code = context.pathParam("code")

        locationRepository.getLocationByCode(code)
            .subscribe(
                { result -> putResponse(context, 200, JsonObject().put("data", result)) },
                { error -> putErrorResponse(context, 404, "Location not found") }
            )
    }

    private fun updateLocation(context: RoutingContext) {
        val locationId = context.pathParam("locationId")
        val body = context.body().asJsonObject()
        val name = body.getString("name")
        val locationType = body.getString("locationType")
        val address = body.getJsonObject("address")

        if (name.isNullOrEmpty() || locationType.isNullOrEmpty()) {
            putErrorResponse(context, 400, "name and locationType are required")
            return
        }

        locationRepository.updateLocation(locationId, name, locationType, address)
            .subscribe(
                { result -> putResponse(context, 200, JsonObject().put("data", result)) },
                { error -> putErrorResponse(context, 404, "Location not found") }
            )
    }

    private fun deleteLocation(context: RoutingContext) {
        val locationId = context.pathParam("locationId")

        locationRepository.deleteLocation(locationId)
            .subscribe(
                { putResponse(context, 204, JsonObject()) },
                { error -> putErrorResponse(context, 500, "Failed to delete location: ${error.message}") }
            )
    }

    // ==================== UTILITY HANDLERS ====================

    private fun getIndex(context: RoutingContext) {
        logger.info("Calling getIndex")

        val response = JsonObject().apply {
            put("success", true)
            put("message", "Literp API Server")
            put("version", "1.0.0")
        }

        putResponse(context, 200, response)
    }

    private fun putResponse(context: RoutingContext, statusCode: Int, response: JsonObject) {
        context.response().statusCode = statusCode
        context.response().putHeader("Content-Type", "application/json")
        context.response().end(response.encode())
    }

    private fun putErrorResponse(context: RoutingContext, statusCode: Int, message: String) {
        val errorResponse = JsonObject()
            .put("error", message)
            .put("status", statusCode)

        putResponse(context, statusCode, errorResponse)
    }
}