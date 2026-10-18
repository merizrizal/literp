package com.literp.service

import com.literp.repository.PosOperationsConflict
import com.literp.repository.PosOperationsScopeViolation
import com.literp.repository.PosOperationsValidation
import com.literp.repository.PosOrderConflict
import com.literp.repository.PosOrderScopeViolation
import com.literp.repository.PosOrderValidation
import io.reactivex.rxjava3.core.Single
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.serviceproxy.ServiceException

fun <T : Any> Single<T>.toVertxFuture(): Future<T> {
    val promise = Promise.promise<T>()
    subscribe(promise::complete) { promise.fail(it.toServiceFailure()) }
    return promise.future()
}

private fun Throwable.toServiceFailure(): Throwable = when (this) {
    is PosOperationsConflict, is PosOrderConflict -> ServiceException(409, message)
    is PosOperationsValidation, is PosOrderValidation -> ServiceException(400, message)
    is PosOperationsScopeViolation, is PosOrderScopeViolation -> ServiceException(403, "Forbidden")
    else -> this
}

fun Single<Unit>.toVertxVoidFuture(): Future<Void> {
    val promise = Promise.promise<Void>()
    subscribe({ promise.complete() }) { promise.fail(it.toServiceFailure()) }
    return promise.future()
}
