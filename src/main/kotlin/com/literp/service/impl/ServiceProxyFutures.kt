package com.literp.service.impl

import io.reactivex.rxjava3.core.Single
import io.vertx.core.Future
import io.vertx.core.Promise

fun <T : Any> Single<T>.toVertxFuture(): Future<T> {
    val promise = Promise.promise<T>()
    subscribe(promise::complete, promise::fail)
    return promise.future()
}

fun Single<Unit>.toVertxVoidFuture(): Future<Void> {
    val promise = Promise.promise<Void>()
    subscribe({ promise.complete() }, promise::fail)
    return promise.future()
}
