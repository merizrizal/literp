package com.literp.verticle

import com.literp.config.Config
import com.literp.common.ErrorCodes
import com.literp.db.DatabaseConnection
import com.literp.repository.LocationRepository
import com.literp.repository.OrderProcessRepository
import com.literp.repository.ProductRepository
import com.literp.repository.ProductVariantRepository
import com.literp.repository.UnitOfMeasureRepository
import com.literp.service.master.LocationService
import com.literp.service.master.ProductService
import com.literp.service.master.ProductVariantService
import com.literp.service.master.UnitOfMeasureService
import com.literp.service.master.impl.LocationServiceImpl
import com.literp.service.master.impl.ProductServiceImpl
import com.literp.service.master.impl.ProductVariantServiceImpl
import com.literp.service.master.impl.UnitOfMeasureServiceImpl
import com.literp.service.order.OrderProcessService
import com.literp.service.order.impl.OrderProcessServiceImpl
import com.literp.verticle.handler.LocationHandler
import com.literp.verticle.handler.OrderProcessHandler
import com.literp.verticle.handler.ProductHandler
import com.literp.verticle.handler.UnitOfMeasureHandler
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.observers.DisposableSingleObserver
import io.vertx.core.Promise
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.internal.logging.LoggerFactory
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.rxjava3.core.Vertx
import io.vertx.rxjava3.sqlclient.Pool
import io.vertx.rxjava3.ext.web.Router
import io.vertx.rxjava3.ext.web.RoutingContext
import io.vertx.rxjava3.ext.web.handler.HSTSHandler
import io.vertx.rxjava3.ext.web.openapi.router.RouterBuilder
import io.vertx.rxjava3.openapi.contract.OpenAPIContract
import java.util.concurrent.TimeUnit

class HttpServerVerticle(
    private val vertx: Vertx
) : CoroutineVerticle() {

    private val logger = LoggerFactory.getLogger(this@HttpServerVerticle.javaClass)
    private lateinit var dbPool: Pool

    private lateinit var uomRepository: UnitOfMeasureRepository
    private lateinit var productRepository: ProductRepository
    private lateinit var variantRepository: ProductVariantRepository
    private lateinit var locationRepository: LocationRepository
    private lateinit var orderProcessRepository: OrderProcessRepository

    private lateinit var uomService: UnitOfMeasureService
    private lateinit var productService: ProductService
    private lateinit var variantService: ProductVariantService
    private lateinit var locationService: LocationService
    private lateinit var orderProcessService: OrderProcessService

    private lateinit var productHandler: ProductHandler
    private lateinit var locationHandler: LocationHandler
    private lateinit var uomHandler: UnitOfMeasureHandler
    private lateinit var orderProcessHandler: OrderProcessHandler

    override fun start(startFuture: Promise<Void>?) {
        val coreVertx = vertx.delegate
        val pool = DatabaseConnection.createPool(vertx)
        dbPool = pool
        uomRepository = UnitOfMeasureRepository(pool)
        productRepository = ProductRepository(pool)
        variantRepository = ProductVariantRepository(pool)
        locationRepository = LocationRepository(pool)
        orderProcessRepository = OrderProcessRepository(pool)

        UnitOfMeasureService.register(coreVertx, UnitOfMeasureServiceImpl(uomRepository))
        ProductService.register(coreVertx, ProductServiceImpl(productRepository))
        ProductVariantService.register(coreVertx, ProductVariantServiceImpl(variantRepository))
        LocationService.register(coreVertx, LocationServiceImpl(locationRepository))
        OrderProcessService.register(coreVertx, OrderProcessServiceImpl(orderProcessRepository))

        uomService = UnitOfMeasureService.createProxy(coreVertx)
        productService = ProductService.createProxy(coreVertx)
        variantService = ProductVariantService.createProxy(coreVertx)
        locationService = LocationService.createProxy(coreVertx)
        orderProcessService = OrderProcessService.createProxy(coreVertx)

        productHandler = ProductHandler(productService, variantService)
        locationHandler = LocationHandler(locationService)
        uomHandler = UnitOfMeasureHandler(uomService)
        orderProcessHandler = OrderProcessHandler(orderProcessService)

        loadApiContracts(startFuture)
    }

    private fun loadApiContracts(startFuture: Promise<Void>?) {
        OpenAPIContract
            .rxFrom(vertx, "api_collections/open_api_spec/product-catalog.yaml")
            .flatMap { productContract ->
                OpenAPIContract
                    .rxFrom(vertx, "api_collections/open_api_spec/locations.yaml")
                    .flatMap { locationContract ->
                        OpenAPIContract
                            .rxFrom(vertx, "api_collections/open_api_spec/order-process.yaml")
                            .map { orderProcessContract -> Triple(productContract, locationContract, orderProcessContract) }
                    }
            }
            .flatMap { (productContract, locationContract, orderProcessContract) ->
                Single.just(Triple(
                    RouterBuilder.create(vertx, productContract),
                    RouterBuilder.create(vertx, locationContract),
                    RouterBuilder.create(vertx, orderProcessContract)
                ))
            }
            .subscribeWith(object : DisposableSingleObserver<Triple<RouterBuilder, RouterBuilder, RouterBuilder>>() {
                override fun onSuccess(routers: Triple<RouterBuilder, RouterBuilder, RouterBuilder>) {
                    logger.info("Deployed OpenAPI Contracts")

                    val (productRouterBuilder, locationRouterBuilder, orderProcessRouterBuilder) = routers

                    registerProductCatalogHandlers(productRouterBuilder)
                    registerLocationHandlers(locationRouterBuilder)
                    registerOrderProcessHandlers(orderProcessRouterBuilder)

                    val productRouter = productRouterBuilder.createRouter()
                    val locationRouter = locationRouterBuilder.createRouter()
                    val orderProcessRouter = orderProcessRouterBuilder.createRouter()

                    val router = Router.router(vertx).apply {
                        route().handler(HSTSHandler.create())

                        get("/").handler(this@HttpServerVerticle::getIndex)
                        get("/health/db").handler(this@HttpServerVerticle::getDatabaseHealth)

                        route("/api/v1/*").subRouter(productRouter)
                        route("/api/v1/*").subRouter(locationRouter)
                        route("/api/v1/*").subRouter(orderProcessRouter)
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
                                logger.error("Fail to deploy HttpServerVerticle: ${failure.message}", failure)
                                startFuture?.fail(failure.cause)
                            }
                        )
                }

                override fun onError(e: Throwable) {
                    logger.error("Fail to deploy OpenAPI Contracts: ${e.message}", e)
                    startFuture?.fail(e.cause)
                }
            })
    }

    private fun registerProductCatalogHandlers(routerBuilder: RouterBuilder) {
        // Unit of Measure handlers (delegated)
        routerBuilder.getRoute("listUnitOfMeasures").addHandler(uomHandler::listUnitOfMeasures)
        routerBuilder.getRoute("createUnitOfMeasure").addHandler(uomHandler::createUnitOfMeasure)
        routerBuilder.getRoute("getUnitOfMeasure").addHandler(uomHandler::getUnitOfMeasure)
        routerBuilder.getRoute("updateUnitOfMeasure").addHandler(uomHandler::updateUnitOfMeasure)
        routerBuilder.getRoute("deleteUnitOfMeasure").addHandler(uomHandler::deleteUnitOfMeasure)

        // Product handlers (delegated)
        routerBuilder.getRoute("listProducts").addHandler(productHandler::listProducts)
        routerBuilder.getRoute("createProduct").addHandler(productHandler::createProduct)
        routerBuilder.getRoute("getProduct").addHandler(productHandler::getProduct)
        routerBuilder.getRoute("updateProduct").addHandler(productHandler::updateProduct)
        routerBuilder.getRoute("deleteProduct").addHandler(productHandler::deleteProduct)

        // Product Variant handlers (delegated)
        routerBuilder.getRoute("listProductVariants").addHandler(productHandler::listProductVariants)
        routerBuilder.getRoute("createProductVariant").addHandler(productHandler::createProductVariant)
        routerBuilder.getRoute("getProductVariant").addHandler(productHandler::getProductVariant)
        routerBuilder.getRoute("updateProductVariant").addHandler(productHandler::updateProductVariant)
        routerBuilder.getRoute("deleteProductVariant").addHandler(productHandler::deleteProductVariant)
    }

    private fun registerLocationHandlers(routerBuilder: RouterBuilder) {
        routerBuilder.getRoute("listLocations").addHandler(locationHandler::listLocations)
        routerBuilder.getRoute("createLocation").addHandler(locationHandler::createLocation)
        routerBuilder.getRoute("getLocation").addHandler(locationHandler::getLocation)
        routerBuilder.getRoute("getLocationByCode").addHandler(locationHandler::getLocationByCode)
        routerBuilder.getRoute("updateLocation").addHandler(locationHandler::updateLocation)
        routerBuilder.getRoute("deleteLocation").addHandler(locationHandler::deleteLocation)
    }

    private fun registerOrderProcessHandlers(routerBuilder: RouterBuilder) {
        routerBuilder.getRoute("listSalesOrders").addHandler(orderProcessHandler::listSalesOrders)
        routerBuilder.getRoute("createSalesOrderDraft").addHandler(orderProcessHandler::createSalesOrderDraft)
        routerBuilder.getRoute("getSalesOrder").addHandler(orderProcessHandler::getSalesOrder)
        routerBuilder.getRoute("addSalesOrderLine").addHandler(orderProcessHandler::addSalesOrderLine)
        routerBuilder.getRoute("confirmSalesOrder").addHandler(orderProcessHandler::confirmSalesOrder)
        routerBuilder.getRoute("capturePayment").addHandler(orderProcessHandler::capturePayment)
        routerBuilder.getRoute("fulfillSalesOrder").addHandler(orderProcessHandler::fulfillSalesOrder)
        routerBuilder.getRoute("cancelSalesOrder").addHandler(orderProcessHandler::cancelSalesOrder)
    }

    private fun getIndex(context: RoutingContext) {
        logger.info("Calling getIndex")

        val response = JsonObject().apply {
            put("success", true)
            put("message", "Literp API Server")
            put("version", "1.0.0")
        }

        putResponse(context, 200, response)
    }

    private fun getDatabaseHealth(context: RoutingContext) {
        dbPool.preparedQuery("SELECT 1")
            .rxExecute()
            .timeout(3, TimeUnit.SECONDS)
            .subscribe(
                {
                    putResponse(
                        context,
                        200,
                        JsonObject()
                            .put("status", "UP")
                            .put("database", "UP")
                    )
                },
                { error ->
                    logger.warn("Database health check failed: ${error.message}", error)
                    val errorCode = if (error is java.util.concurrent.TimeoutException) {
                        ErrorCodes.DB_TIMEOUT
                    } else {
                        ErrorCodes.INTERNAL_ERROR
                    }
                    putResponse(
                        context,
                        503,
                        JsonObject()
                            .put("status", "DOWN")
                            .put("database", "DOWN")
                            .put("errorCode", errorCode)
                            .put("error", error.message ?: "Database unavailable")
                    )
                }
            )
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
