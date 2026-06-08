package com.literp.service.master.impl

import com.literp.repository.ProductRepository
import com.literp.service.master.ProductService
import com.literp.service.toVertxFuture
import com.literp.service.toVertxVoidFuture
import io.vertx.core.Future
import io.vertx.core.json.JsonObject

class ProductServiceImpl(
    private val repository: ProductRepository
) : ProductService {

    override fun listProducts(
        page: Int,
        size: Int,
        sort: String,
        sku: String?,
        productType: String?,
        activeOnly: Boolean
    ): Future<JsonObject> {
        return repository.listProducts(page, size, sort, sku, productType, activeOnly).toVertxFuture()
    }

    override fun createProduct(
        sku: String,
        name: String,
        productType: String,
        baseUom: String,
        active: Boolean,
        metadata: JsonObject?
    ): Future<JsonObject> {
        return repository.createProduct(sku, name, productType, baseUom, active, metadata).toVertxFuture()
    }

    override fun getProduct(productId: String, includeVariants: Boolean): Future<JsonObject> {
        return repository.getProduct(productId, includeVariants).toVertxFuture()
    }

    override fun updateProduct(
        productId: String,
        name: String,
        productType: String,
        baseUom: String?,
        active: Boolean?,
        metadata: JsonObject?
    ): Future<JsonObject> {
        return repository.updateProduct(productId, name, productType, baseUom, active, metadata).toVertxFuture()
    }

    override fun deleteProduct(productId: String): Future<Void> {
        return repository.deleteProduct(productId).toVertxVoidFuture()
    }

    override fun checkSkuExists(sku: String): Future<Boolean> {
        return repository.checkSkuExists(sku).toVertxFuture()
    }
}
