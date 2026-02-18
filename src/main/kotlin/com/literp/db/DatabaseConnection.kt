package com.literp.db

import com.literp.config.Config
import io.vertx.core.internal.logging.LoggerFactory
import io.vertx.pgclient.PgConnectOptions
import io.vertx.rxjava3.core.Vertx
import io.vertx.rxjava3.sqlclient.Pool as RxPool
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PoolOptions

object DatabaseConnection {

    private val logger = LoggerFactory.getLogger(this.javaClass)

    fun createPool(vertx: Vertx): RxPool {
        logger.info("Initializing database pool")
        val config = Config()

        val connectOptions = PgConnectOptions()
            .setPort(config.pgPort)
            .setHost(config.pgHost)
            .setDatabase(config.pgDatabase)
            .setUser(config.pgUser)
            .setPassword(config.pgPassword)

        val poolOptions = PoolOptions()
            .setMaxSize(5)
            .setConnectionTimeout(120000)

        val pool = Pool.pool(vertx.delegate,connectOptions, poolOptions)
        logger.info("Database pool created")
        return RxPool.newInstance(pool)
    }
}
