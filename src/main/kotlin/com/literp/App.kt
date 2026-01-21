package com.literp

import com.literp.verticle.MainVerticle
import io.vertx.core.Vertx
import io.vertx.core.internal.logging.LoggerFactory

fun main() {
    val logger = LoggerFactory.getLogger("main")
    val vertx = Vertx.vertx()

    vertx.deployVerticle(MainVerticle())
        .onSuccess { id ->
            logger.info("Vertx deployed with id $id")
        }
        .onFailure { err ->
            logger.error("Vertx deployed with error $err")
        }
}