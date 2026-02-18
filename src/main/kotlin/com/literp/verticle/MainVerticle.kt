package com.literp.verticle

import io.vertx.core.Promise
import io.vertx.core.internal.logging.LoggerFactory
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.rxjava3.core.RxHelper
import io.vertx.rxjava3.core.Vertx

class MainVerticle : CoroutineVerticle() {

    private val logger = LoggerFactory.getLogger(this@MainVerticle.javaClass)
    val vertx = Vertx.vertx()

    override fun start(startFuture: Promise<Void>?) {
        RxHelper
            .deployVerticle(vertx, HttpServerVerticle(vertx))
            .subscribe(
                {
                    logger.info("Deployed MainVerticle")
                    startFuture?.complete()
                },
                { failure ->
                    logger.error("Fail to deploy MainVerticle: ${failure.message}")
                    startFuture?.fail(failure.message)
                }
            )
    }
}