package com.literp.repository

import io.vertx.core.internal.logging.LoggerFactory
import io.vertx.rxjava3.sqlclient.Pool

abstract class BaseRepository(protected val pool: Pool, clazz: Class<*>) {
    protected val logger = LoggerFactory.getLogger(clazz)

    init {
        pool.rxGetConnection()
            .subscribe(
                { conn ->
                    logger.info("DB Connection established for ${clazz.simpleName} repository")
                    // Release startup probe connection back to pool immediately.
                    conn.close()
                },
                { error -> logger.error("Error establishing DB connection for ${clazz.simpleName}", error) }
            )
    }
}
