package com.literp.repository

import io.vertx.core.internal.logging.LoggerFactory
import io.vertx.rxjava3.sqlclient.Pool

abstract class BaseRepository(protected val pool: Pool, clazz: Class<*>) {
    protected val logger = LoggerFactory.getLogger(clazz)

    init {
        pool.rxGetConnection()
            .subscribe(
                { _ -> logger.info("DB Connection established for ${clazz.simpleName} repository") },
                { error -> logger.error("Error establishing DB connection for ${clazz.simpleName}", error) }
            )
    }
}
