package com.literp.verticle

import com.literp.common.ErrorCodes
import com.literp.config.Config
import com.literp.db.DatabaseConnection
import com.literp.observability.HttpMetrics
import com.literp.repository.LocationRepository
import com.literp.repository.OrderProcessRepository
import com.literp.repository.OrderScopeRepository
import com.literp.repository.PosOperationsRepository
import com.literp.repository.ProductRepository
import com.literp.repository.ProductVariantRepository
import com.literp.repository.UnitOfMeasureRepository
import com.literp.security.ExplicitSecurityPolicy
import com.literp.security.JwtCredentialVerifier
import com.literp.security.SecurityConfig
import com.literp.security.UtilityOperationIds
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
import com.literp.service.pos.PosOperationsService
import com.literp.service.pos.impl.PosOperationsServiceImpl
import com.literp.verticle.handler.AuthenticatedActorAdapter
import com.literp.verticle.handler.LocationHandler
import com.literp.verticle.handler.OpenApiBearerAuthenticationHandler
import com.literp.verticle.handler.OrderProcessHandler
import com.literp.verticle.handler.OrderScopeHandler
import com.literp.verticle.handler.PosOperationsHandler
import com.literp.verticle.handler.PosScopeHandler
import com.literp.verticle.handler.ProductHandler
import com.literp.verticle.handler.SecurityHandler
import com.literp.verticle.handler.UnitOfMeasureHandler
import io.reactivex.rxjava3.observers.DisposableSingleObserver
import io.vertx.core.Promise
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.internal.logging.LoggerFactory
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.handler.HttpException
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.rxjava3.core.Vertx
import io.vertx.rxjava3.ext.web.Router
import io.vertx.rxjava3.ext.web.RoutingContext
import io.vertx.rxjava3.ext.web.handler.HSTSHandler
import io.vertx.rxjava3.ext.web.openapi.router.RouterBuilder
import io.vertx.rxjava3.openapi.contract.OpenAPIContract
import io.vertx.rxjava3.sqlclient.Pool
import java.util.UUID
import java.util.concurrent.TimeUnit

class HttpServerVerticle(
    private val vertx: Vertx
) : CoroutineVerticle() {

    private var injectedSecurityHandler: SecurityHandler? = null

    internal constructor(vertx: Vertx, securityHandler: SecurityHandler) : this(vertx) {
        injectedSecurityHandler = securityHandler
    }

    private val logger = LoggerFactory.getLogger(this@HttpServerVerticle.javaClass)
    private lateinit var dbPool: Pool

    private lateinit var uomRepository: UnitOfMeasureRepository
    private lateinit var productRepository: ProductRepository
    private lateinit var variantRepository: ProductVariantRepository
    private lateinit var locationRepository: LocationRepository
    private lateinit var orderProcessRepository: OrderProcessRepository
    private lateinit var posOperationsRepository: PosOperationsRepository

    private lateinit var securityHandler: SecurityHandler
    private lateinit var orderScopeHandler: OrderScopeHandler
    private lateinit var posScopeHandler: PosScopeHandler
    private lateinit var actorAdapter: AuthenticatedActorAdapter

    private lateinit var uomService: UnitOfMeasureService
    private lateinit var productService: ProductService
    private lateinit var variantService: ProductVariantService
    private lateinit var locationService: LocationService
    private lateinit var orderProcessService: OrderProcessService
    private lateinit var posOperationsService: PosOperationsService

    private lateinit var productHandler: ProductHandler
    private lateinit var locationHandler: LocationHandler
    private lateinit var uomHandler: UnitOfMeasureHandler
    private lateinit var orderProcessHandler: OrderProcessHandler
    private lateinit var posOperationsHandler: PosOperationsHandler
    private val metrics = HttpMetrics()

    private data class ApiRouterBuilders(
        val product: RouterBuilder,
        val location: RouterBuilder,
        val orderProcess: RouterBuilder,
        val pos: RouterBuilder
    )

    private companion object {
        const val METRICS_START_NANOS_KEY = "metricsStartNanos"
        const val REQUEST_ID_HEADER = "X-Request-ID"
        const val REQUEST_ID_CONTEXT_KEY = "requestId"
    }

    override fun start(startFuture: Promise<Void>?) {
        val coreVertx = vertx.delegate
        try {
            securityHandler = injectedSecurityHandler ?: createProductionSecurityHandler(coreVertx)
        } catch (failure: Throwable) {
            logger.error("Fail to initialize HTTP security: ${failure.message}", failure)
            startFuture?.fail(failure)
            return
        }

        val pool = DatabaseConnection.createPool(vertx)
        dbPool = pool
        uomRepository = UnitOfMeasureRepository(pool)
        productRepository = ProductRepository(pool)
        variantRepository = ProductVariantRepository(pool)
        locationRepository = LocationRepository(pool)
        orderProcessRepository = OrderProcessRepository(pool)
        posOperationsRepository = PosOperationsRepository(pool)

        UnitOfMeasureService.register(coreVertx, UnitOfMeasureServiceImpl(uomRepository))
        ProductService.register(coreVertx, ProductServiceImpl(productRepository))
        ProductVariantService.register(coreVertx, ProductVariantServiceImpl(variantRepository))
        LocationService.register(coreVertx, LocationServiceImpl(locationRepository))
        OrderProcessService.register(coreVertx, OrderProcessServiceImpl(orderProcessRepository))
        PosOperationsService.register(coreVertx, PosOperationsServiceImpl(posOperationsRepository))

        uomService = UnitOfMeasureService.createProxy(coreVertx)
        productService = ProductService.createProxy(coreVertx)
        variantService = ProductVariantService.createProxy(coreVertx)
        locationService = LocationService.createProxy(coreVertx)
        orderProcessService = OrderProcessService.createProxy(coreVertx)
        posOperationsService = PosOperationsService.createProxy(coreVertx)

        productHandler = ProductHandler(productService, variantService)
        locationHandler = LocationHandler(locationService)
        uomHandler = UnitOfMeasureHandler(uomService)
        actorAdapter = AuthenticatedActorAdapter()
        orderScopeHandler = OrderScopeHandler(OrderScopeRepository(pool), actorAdapter)
        posScopeHandler = PosScopeHandler()
        orderProcessHandler = OrderProcessHandler(orderProcessService, actorAdapter)
        posOperationsHandler = PosOperationsHandler(posOperationsService)

        loadApiContracts(startFuture)
    }

    private fun createProductionSecurityHandler(coreVertx: io.vertx.core.Vertx): SecurityHandler {
        val config = SecurityConfig.fromEnvironment()
        return SecurityHandler(
            JwtCredentialVerifier(coreVertx, config),
            ExplicitSecurityPolicy(config.organizationId)
        )
    }

    private fun configureOpenApiSecurity(routerBuilder: RouterBuilder): RouterBuilder =
        routerBuilder
            .security("bearerAuth")
            .httpHandler(OpenApiBearerAuthenticationHandler(securityHandler).asRxHandler())

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
                OpenAPIContract
                    .rxFrom(vertx, "api_collections/open_api_spec/pos-operations.yaml")
                    .map { posContract ->
                        ApiRouterBuilders(
                            product = configureOpenApiSecurity(RouterBuilder.create(vertx, productContract)),
                            location = configureOpenApiSecurity(RouterBuilder.create(vertx, locationContract)),
                            orderProcess = configureOpenApiSecurity(RouterBuilder.create(vertx, orderProcessContract)),
                            pos = configureOpenApiSecurity(RouterBuilder.create(vertx, posContract))
                        )
                    }
            }
            .subscribeWith(object : DisposableSingleObserver<ApiRouterBuilders>() {
                override fun onSuccess(routers: ApiRouterBuilders) {
                    logger.info("Deployed OpenAPI Contracts")

                    val (productRouterBuilder, locationRouterBuilder, orderProcessRouterBuilder, posRouterBuilder) = routers

                    registerProductCatalogHandlers(productRouterBuilder)
                    registerLocationHandlers(locationRouterBuilder)
                    registerOrderProcessHandlers(orderProcessRouterBuilder)
                    registerPosOperationsHandlers(posRouterBuilder)

                    val productRouter = productRouterBuilder.createRouter()
                    val locationRouter = locationRouterBuilder.createRouter()
                    val orderProcessRouter = orderProcessRouterBuilder.createRouter()
                    val posRouter = posRouterBuilder.createRouter()

                    val router = Router.router(vertx).apply {
                        route().handler(HSTSHandler.create())
                        route().handler(this@HttpServerVerticle::captureRequestId)
                        route().handler(this@HttpServerVerticle::captureRequestMetrics)
                        route().handler(this@HttpServerVerticle::authenticateOrAllowPublic)
                        route().failureHandler(this@HttpServerVerticle::handleFailure)

                        get("/").handler(this@HttpServerVerticle::getIndex)
                        get("/metrics")
                            .handler(securityHandler.authorizeOperation(UtilityOperationIds.METRICS))
                            .handler(this@HttpServerVerticle::getMetrics)
                        get("/health/live").handler(this@HttpServerVerticle::getLiveness)
                        get("/health/ready")
                            .handler(securityHandler.authorizeOperation(UtilityOperationIds.HEALTH_READY))
                            .handler(this@HttpServerVerticle::getReadiness)
                        get("/health/db")
                            .handler(securityHandler.authorizeOperation(UtilityOperationIds.HEALTH_DB))
                            .handler(this@HttpServerVerticle::getDatabaseHealth)

                        route("/api/v1/*").subRouter(productRouter)
                        route("/api/v1/*").subRouter(locationRouter)
                        route("/api/v1/*").subRouter(orderProcessRouter)
                        route("/api/v1/*").subRouter(posRouter)
                        route().handler { context -> context.fail(404) }
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
        routerBuilder.getRoute("listUnitOfMeasures")
            .addHandler(securityHandler.authorizeOperation("listUnitOfMeasures"))
            .addHandler(uomHandler::listUnitOfMeasures)
        routerBuilder.getRoute("createUnitOfMeasure")
            .addHandler(securityHandler.authorizeOperation("createUnitOfMeasure"))
            .addHandler(uomHandler::createUnitOfMeasure)
        routerBuilder.getRoute("getUnitOfMeasure")
            .addHandler(securityHandler.authorizeOperation("getUnitOfMeasure"))
            .addHandler(uomHandler::getUnitOfMeasure)
        routerBuilder.getRoute("updateUnitOfMeasure")
            .addHandler(securityHandler.authorizeOperation("updateUnitOfMeasure"))
            .addHandler(uomHandler::updateUnitOfMeasure)
        routerBuilder.getRoute("deleteUnitOfMeasure")
            .addHandler(securityHandler.authorizeOperation("deleteUnitOfMeasure"))
            .addHandler(uomHandler::deleteUnitOfMeasure)

        // Product handlers (delegated)
        routerBuilder.getRoute("listProducts")
            .addHandler(securityHandler.authorizeOperation("listProducts"))
            .addHandler(productHandler::listProducts)
        routerBuilder.getRoute("createProduct")
            .addHandler(securityHandler.authorizeOperation("createProduct"))
            .addHandler(productHandler::createProduct)
        routerBuilder.getRoute("getProduct")
            .addHandler(securityHandler.authorizeOperation("getProduct"))
            .addHandler(productHandler::getProduct)
        routerBuilder.getRoute("updateProduct")
            .addHandler(securityHandler.authorizeOperation("updateProduct"))
            .addHandler(productHandler::updateProduct)
        routerBuilder.getRoute("deleteProduct")
            .addHandler(securityHandler.authorizeOperation("deleteProduct"))
            .addHandler(productHandler::deleteProduct)

        // Product Variant handlers (delegated)
        routerBuilder.getRoute("listProductVariants")
            .addHandler(securityHandler.authorizeOperation("listProductVariants"))
            .addHandler(productHandler::listProductVariants)
        routerBuilder.getRoute("createProductVariant")
            .addHandler(securityHandler.authorizeOperation("createProductVariant"))
            .addHandler(productHandler::createProductVariant)
        routerBuilder.getRoute("getProductVariant")
            .addHandler(securityHandler.authorizeOperation("getProductVariant"))
            .addHandler(productHandler::getProductVariant)
        routerBuilder.getRoute("updateProductVariant")
            .addHandler(securityHandler.authorizeOperation("updateProductVariant"))
            .addHandler(productHandler::updateProductVariant)
        routerBuilder.getRoute("deleteProductVariant")
            .addHandler(securityHandler.authorizeOperation("deleteProductVariant"))
            .addHandler(productHandler::deleteProductVariant)
    }

    private fun registerLocationHandlers(routerBuilder: RouterBuilder) {
        routerBuilder.getRoute("listLocations")
            .addHandler(securityHandler.authorizeOperation("listLocations"))
            .addHandler(locationHandler::listLocations)
        routerBuilder.getRoute("createLocation")
            .addHandler(securityHandler.authorizeOperation("createLocation"))
            .addHandler(locationHandler::createLocation)
        routerBuilder.getRoute("getLocation")
            .addHandler(securityHandler.authorizeOperation("getLocation"))
            .addHandler(locationHandler::getLocation)
        routerBuilder.getRoute("getLocationByCode")
            .addHandler(securityHandler.authorizeOperation("getLocationByCode"))
            .addHandler(locationHandler::getLocationByCode)
        routerBuilder.getRoute("updateLocation")
            .addHandler(securityHandler.authorizeOperation("updateLocation"))
            .addHandler(locationHandler::updateLocation)
        routerBuilder.getRoute("deleteLocation")
            .addHandler(securityHandler.authorizeOperation("deleteLocation"))
            .addHandler(locationHandler::deleteLocation)
    }

    private fun registerOrderProcessHandlers(routerBuilder: RouterBuilder) {
        routerBuilder.getRoute("listSalesOrders")
            .addHandler(securityHandler.authorizeOperation("listSalesOrders"))
            .addHandler(orderScopeHandler::authorize)
        routerBuilder.getRoute("createSalesOrderDraft")
            .addHandler(securityHandler.authorizeOperation("createSalesOrderDraft"))
            .addHandler(orderScopeHandler::authorize)
            .addHandler(orderProcessHandler::createSalesOrderDraft)
        routerBuilder.getRoute("getSalesOrder")
            .addHandler(securityHandler.authorizeOperation("getSalesOrder"))
            .addHandler(orderScopeHandler::authorize)
            .addHandler(orderProcessHandler::getSalesOrder)
        routerBuilder.getRoute("getCurrentStock")
            .addHandler(securityHandler.authorizeOperation("getCurrentStock"))
            .addHandler(orderScopeHandler::authorize)
            .addHandler(orderProcessHandler::getCurrentStock)
        routerBuilder.getRoute("getAvailableStock")
            .addHandler(securityHandler.authorizeOperation("getAvailableStock"))
            .addHandler(orderScopeHandler::authorize)
            .addHandler(orderProcessHandler::getAvailableStock)
        routerBuilder.getRoute("addSalesOrderLine")
            .addHandler(securityHandler.authorizeOperation("addSalesOrderLine"))
            .addHandler(orderScopeHandler::authorize)
            .addHandler(orderProcessHandler::addSalesOrderLine)
        routerBuilder.getRoute("confirmSalesOrder")
            .addHandler(securityHandler.authorizeOperation("confirmSalesOrder"))
            .addHandler(orderScopeHandler::authorize)
            .addHandler(orderProcessHandler::confirmSalesOrder)
        routerBuilder.getRoute("capturePayment")
            .addHandler(securityHandler.authorizeOperation("capturePayment"))
            .addHandler(orderScopeHandler::authorize)
            .addHandler(orderProcessHandler::capturePayment)
        routerBuilder.getRoute("fulfillSalesOrder")
            .addHandler(securityHandler.authorizeOperation("fulfillSalesOrder"))
            .addHandler(orderScopeHandler::authorize)
            .addHandler(orderProcessHandler::fulfillSalesOrder)
        routerBuilder.getRoute("cancelSalesOrder")
            .addHandler(securityHandler.authorizeOperation("cancelSalesOrder"))
            .addHandler(orderScopeHandler::authorize)
            .addHandler(orderProcessHandler::cancelSalesOrder)
    }

    private fun registerPosOperationsHandlers(routerBuilder: RouterBuilder) {
        routerBuilder.getRoute("listPosTerminals")
            .addHandler(securityHandler.authorizeOperation("listPosTerminals"))
            .addHandler(posScopeHandler::authorize)
            .addHandler(posOperationsHandler::listPosTerminals)
        routerBuilder.getRoute("createPosTerminal")
            .addHandler(securityHandler.authorizeOperation("createPosTerminal"))
            .addHandler(posScopeHandler::authorize)
            .addHandler(posOperationsHandler::createPosTerminal)
        routerBuilder.getRoute("getPosTerminal")
            .addHandler(securityHandler.authorizeOperation("getPosTerminal"))
            .addHandler(posScopeHandler::authorize)
            .addHandler(posOperationsHandler::getPosTerminal)
        routerBuilder.getRoute("updatePosTerminal")
            .addHandler(securityHandler.authorizeOperation("updatePosTerminal"))
            .addHandler(posScopeHandler::authorize)
            .addHandler(posOperationsHandler::updatePosTerminal)
        routerBuilder.getRoute("deactivatePosTerminal")
            .addHandler(securityHandler.authorizeOperation("deactivatePosTerminal"))
            .addHandler(posScopeHandler::authorize)
            .addHandler(posOperationsHandler::deactivatePosTerminal)
        routerBuilder.getRoute("openPosShift")
            .addHandler(securityHandler.authorizeOperation("openPosShift"))
            .addHandler(posScopeHandler::authorize)
            .addHandler(posOperationsHandler::openPosShift)
        routerBuilder.getRoute("getCurrentPosShift")
            .addHandler(securityHandler.authorizeOperation("getCurrentPosShift"))
            .addHandler(posScopeHandler::authorize)
            .addHandler(posOperationsHandler::getCurrentPosShift)
        routerBuilder.getRoute("closePosShift")
            .addHandler(securityHandler.authorizeOperation("closePosShift"))
            .addHandler(posScopeHandler::authorize)
            .addHandler(posOperationsHandler::closePosShift)
        routerBuilder.getRoute("getPosReceiptByNumber")
            .addHandler(securityHandler.authorizeOperation("getPosReceiptByNumber"))
            .addHandler(posOperationsHandler::getPosReceiptByNumber)
        routerBuilder.getRoute("listPosReceiptsBySalesOrder")
            .addHandler(securityHandler.authorizeOperation("listPosReceiptsBySalesOrder"))
            .addHandler(posOperationsHandler::listPosReceiptsBySalesOrder)
    }

    private fun getIndex(context: RoutingContext) {
        logger.info("Calling getIndex")

        val response = JsonObject().apply {
            put("success", true)
            put("message", "Literp API Server")
            put("version", "0.0.1")
        }

        putResponse(context, 200, response)
    }

    private fun captureRequestId(context: RoutingContext) {
        resolveRequestId(context)
        context.next()
    }

    private fun authenticateOrAllowPublic(context: RoutingContext) {
        val request = context.request()
        if (request != null && request.method() == HttpMethod.GET && isPublicPath(request.path())) {
            context.next()
        } else {
            securityHandler.authenticate(context)
        }
    }

    private fun isPublicPath(path: String?): Boolean = path == "/" || path == "/health/live"

    private fun captureRequestMetrics(context: RoutingContext) {
        val startedAt = System.nanoTime()
        try {
            context.put(METRICS_START_NANOS_KEY, startedAt)
            context.response().endHandler {
                val resolvedStartedAt = context.get<Long>(METRICS_START_NANOS_KEY) ?: startedAt
                try {
                    metrics.recordRequest(context.response().statusCode, System.nanoTime() - resolvedStartedAt)
                } catch (error: Throwable) {
                    logger.warn("Failed to record request metrics: ${error.message}", error)
                }
            }
        } catch (error: Throwable) {
            logger.warn("Failed to install request metrics handler: ${error.message}", error)
        } finally {
            context.next()
        }
    }

    private fun getMetrics(context: RoutingContext) {
        putResponse(context, 200, metrics.snapshot())
    }

    private fun getLiveness(context: RoutingContext) {
        logger.info("Calling getLiveness")

        putResponse(
            context,
            200,
            JsonObject().put("status", "UP")
        )
    }

    private fun getReadiness(context: RoutingContext) {
        getDatabaseHealth(context)
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
                    metrics.recordDatabaseFailure()
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

    private fun handleFailure(context: RoutingContext) {
        val failure = context.failure()
        val statusCode = when {
            failure is HttpException -> failure.statusCode
            context.statusCode() > 0 -> context.statusCode()
            else -> 500
        }
        val message = when (statusCode) {
            401 -> "Authentication required"
            403 -> "Forbidden"
            else -> failure?.message ?: if (statusCode == 400) "Bad request" else "Internal server error"
        }
        if (statusCode == 401) {
            context.response().putHeader("WWW-Authenticate", "Bearer")
        }

        putResponse(
            context,
            statusCode,
            JsonObject()
                .put("error", message)
                .put("errorCode", ErrorCodes.fromStatus(statusCode))
                .put("status", statusCode)
                .put("errorId", UUID.randomUUID().toString())
        )
    }

    private fun putResponse(context: RoutingContext, statusCode: Int, response: JsonObject) {
        val requestId = resolveRequestId(context)
        context.response().statusCode = statusCode
        context.response().putHeader("Content-Type", "application/json")
        context.response().putHeader(REQUEST_ID_HEADER, requestId)
        context.response().end(response.encode())
    }

    private fun resolveRequestId(context: RoutingContext): String {
        val existingRequestId = context.get<String>(REQUEST_ID_CONTEXT_KEY)
        if (!existingRequestId.isNullOrBlank()) {
            return existingRequestId
        }

        val requestId = context.request()?.getHeader(REQUEST_ID_HEADER)?.trim().orEmpty().ifBlank {
            UUID.randomUUID().toString()
        }
        context.put(REQUEST_ID_CONTEXT_KEY, requestId)
        return requestId
    }

    private fun putErrorResponse(context: RoutingContext, statusCode: Int, message: String) {
        val errorResponse = JsonObject()
            .put("error", message)
            .put("status", statusCode)

        putResponse(context, statusCode, errorResponse)
    }
}
