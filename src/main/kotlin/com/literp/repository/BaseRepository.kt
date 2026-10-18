package com.literp.repository

import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.core.Maybe
import java.util.concurrent.atomic.AtomicBoolean
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

    fun beginExecution(requestId: String, operation: String): (Boolean) -> Unit {
        val finished = AtomicBoolean(false)
        val started = System.nanoTime()
        fun log(outcome: String) {
            logger.info(JsonObject().put("event", "repository.execution").put("repository", javaClass.simpleName)
                .put("requestId", requestId).put("operation", operation).put("outcome", outcome)
                .put("durationNanos", System.nanoTime() - started).encode())
        }
        log("started")
        return { success -> if (finished.compareAndSet(false, true)) log(if (success) "succeeded" else "failed") }
    }

    fun <T : Any> trace(requestId: String, operation: String, work: Single<T>): Single<T> = Single.defer {
        val complete = beginExecution(requestId, operation)
        work.doOnSuccess { complete(true) }.doOnError { complete(false) }
    }

    fun <T : Any> trace(requestId: String, operation: String, work: Maybe<T>): Maybe<T> = Maybe.defer {
        val complete = beginExecution(requestId, operation)
        work.doOnSuccess { complete(true) }.doOnComplete { complete(true) }.doOnError { complete(false) }
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

    protected fun isUniqueViolation(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is PgException && current.sqlState == POSTGRES_UNIQUE_VIOLATION) {
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
        private const val POSTGRES_UNIQUE_VIOLATION = "23505"
    }
}
