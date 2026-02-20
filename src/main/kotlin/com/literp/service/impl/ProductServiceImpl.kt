package com.literp.service.impl

import com.literp.repository.ProductRepository
import com.literp.service.ProductService
import io.vertx.core.Future
import io.vertx.core.json.JsonObject

class ProductServiceImpl(
    private val repository: ProductRepository
) : ProductService {

    override fun listProducts(page: Int, size: Int, sort: String): Future<JsonObject> {
        return repository.listProducts(page, size, sort).toVertxFuture()
    }

    override fun createProduct(
        sku: String,
        name: String,
        productType: String,
        baseUom: String,
        metadata: JsonObject?
    ): Future<JsonObject> {
        return repository.createProduct(sku, name, productType, baseUom, metadata).toVertxFuture()
    }

    override fun getProduct(productId: String): Future<JsonObject> {
        return repository.getProduct(productId).toVertxFuture()
    }

    override fun updateProduct(
        productId: String,
        name: String,
        productType: String,
        metadata: JsonObject?
    ): Future<JsonObject> {
        return repository.updateProduct(productId, name, productType, metadata).toVertxFuture()
    }

    override fun deleteProduct(productId: String): Future<Void> {
        return repository.deleteProduct(productId).toVertxVoidFuture()
    }

    override fun checkSkuExists(sku: String): Future<Boolean> {
        return repository.checkSkuExists(sku).toVertxFuture()
    }
}
