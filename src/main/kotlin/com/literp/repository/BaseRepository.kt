package com.literp.repository

import io.reactivex.rxjava3.core.Single
import io.vertx.core.internal.logging.LoggerFactory
import io.vertx.core.json.JsonObject
import io.vertx.pgclient.PgException
import io.vertx.rxjava3.sqlclient.Pool
import io.vertx.rxjava3.sqlclient.SqlConnection

abstract class BaseRepository(protected val pool: Pool, clazz: Class<*>) {
    protected val logger = LoggerFactory.getLogger(clazz)!!

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

    protected fun <T : Any> inTransaction(work: (SqlConnection) -> Single<T>): Single<T> {
        return pool.rxWithTransaction { connection ->
            work(connection).toMaybe()
        }.toSingle()
    }

    protected fun isForeignKeyViolation(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is PgException && current.sqlState == POSTGRES_FOREIGN_KEY_VIOLATION) {
                return true
            }
            current = current.cause
        }

        return false
    }

    protected fun jsonObjectOrEmpty(value: String?): JsonObject {
        return if (value.isNullOrBlank()) JsonObject() else JsonObject(value)
    }

    private companion object {
        private const val POSTGRES_FOREIGN_KEY_VIOLATION = "23503"
    }
}
