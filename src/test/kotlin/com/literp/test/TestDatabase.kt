package com.literp.test

import io.vertx.pgclient.PgConnectOptions
import io.vertx.rxjava3.core.Vertx
import io.vertx.rxjava3.sqlclient.Pool
import io.vertx.sqlclient.PoolOptions
import org.junit.jupiter.api.Assumptions.assumeTrue

object TestDatabase {
    const val SEED_UOM_UNIT = "e6d51210-c046-4d7b-afce-2f33b5846dd1"

    fun createPool(vertx: Vertx): Pool {
        val connectOptions = PgConnectOptions()
            .setHost(env("LITERP_TEST_PG_HOST", "127.0.0.1"))
            .setPort(env("LITERP_TEST_PG_PORT", "55432").toInt())
            .setUser(env("LITERP_TEST_PG_USER", "root"))
            .setPassword(env("LITERP_TEST_PG_PASSWORD", "pgdevpassword"))
            .setDatabase(env("LITERP_TEST_PG_DATABASE", "literp_test"))

        val pool = io.vertx.sqlclient.Pool.pool(vertx.delegate, connectOptions, PoolOptions().setMaxSize(4))
        return Pool.newInstance(pool)
    }

    fun assumeAvailable(pool: Pool) {
        try {
            pool.preparedQuery("SELECT 1").rxExecute().blockingGet()
        } catch (error: Throwable) {
            assumeTrue(false, "PostgreSQL test database is not available on 127.0.0.1:55432: ${error.message}")
        }
    }

    private fun env(name: String, defaultValue: String): String {
        return System.getenv(name)?.takeIf { it.isNotBlank() } ?: defaultValue
    }
}
