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

    // Test-only cleanup on one transaction-local connection. Never disables triggers globally.
    // The production append-only invariant is separately exercised through normal connections.
    fun deleteRefundFixtures(pool: Pool, orderId: String) {
        pool.rxWithTransaction { connection ->
            connection.query("SELECT current_database() AS name").rxExecute().flatMap { rows ->
                check(rows.first().getString("name") == env("LITERP_TEST_PG_DATABASE", "literp_test"))
                connection.query("SET LOCAL session_replication_role = replica").rxExecute()
            }.flatMap {
                connection.preparedQuery("DELETE FROM pos_receipt_refund WHERE receipt_id IN (SELECT receipt_id FROM receipt WHERE sales_order_id = $1)")
                    .rxExecute(io.vertx.rxjava3.sqlclient.Tuple.of(orderId))
            }.toMaybe()
        }.blockingGet()
    }

    private fun env(name: String, defaultValue: String): String {
        return System.getenv(name)?.takeIf { it.isNotBlank() } ?: defaultValue
    }
}
