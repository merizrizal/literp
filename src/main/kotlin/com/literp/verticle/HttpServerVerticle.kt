package com.literp.verticle

import com.literp.config.Config
import io.vertx.core.Promise
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.internal.logging.LoggerFactory
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.rxjava3.core.Vertx
import io.vertx.rxjava3.ext.web.Router
import io.vertx.rxjava3.ext.web.RoutingContext
import io.vertx.rxjava3.ext.web.handler.HSTSHandler

class HttpServerVerticle(
    private val vertx: Vertx
) : CoroutineVerticle() {
    private val logger = LoggerFactory.getLogger(this@HttpServerVerticle.javaClass)

    override fun start(startFuture: Promise<Void>?) {
        val router = Router.router(vertx).apply {
            get("/").handler(this@HttpServerVerticle::getIndex)
        }

        router.route().handler(HSTSHandler.create())

        val config = Config()

        val httpOptions = HttpServerOptions()
            .setPort(config.httpPort)
//            .setSsl(true)
//            .setKeyCertOptions(PemKeyCertOptions()
//                .setKeyPath("/path/to/certkey.pem")
//                .setCertPath("/path/to/cert.pem"))

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

    private fun getIndex(context: RoutingContext) {
        logger.info("Calling index")
        putResponse(context, 200, JsonObject().put("success", true))
    }

    private fun putResponse(context: RoutingContext, statusCode: Int, response: JsonObject) {
        context.response().statusCode = statusCode

        context.response().putHeader("Content-Type", "application/json")
        context.response().end(response.encode())
    }
}