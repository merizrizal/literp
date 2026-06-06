package com.literp.service.master.impl

import com.literp.repository.ProductVariantRepository
import com.literp.service.master.ProductVariantService
import com.literp.service.toVertxFuture
import com.literp.service.toVertxVoidFuture
import io.vertx.core.Future
import io.vertx.core.json.JsonObject

class ProductVariantServiceImpl(
    private val repository: ProductVariantRepository
) : ProductVariantService {

    override fun listProductVariants(
        productId: String,
        page: Int,
        size: Int,
        sort: String,
        activeOnly: Boolean
    ): Future<JsonObject> {
        return repository.listProductVariants(productId, page, size, sort, activeOnly).toVertxFuture()
    }

    override fun createProductVariant(
        productId: String,
        sku: String,
        name: String,
        active: Boolean,
        attributes: JsonObject?
    ): Future<JsonObject> {
        return repository.createProductVariant(productId, sku, name, active, attributes).toVertxFuture()
    }

    override fun getProductVariant(productId: String, variantId: String): Future<JsonObject> {
        return repository.getProductVariant(productId, variantId).toVertxFuture()
    }

    override fun updateProductVariant(
        productId: String,
        variantId: String,
        name: String,
        active: Boolean?,
        attributes: JsonObject?
    ): Future<JsonObject> {
        return repository.updateProductVariant(productId, variantId, name, active, attributes).toVertxFuture()
    }

    override fun deleteProductVariant(productId: String, variantId: String): Future<Void> {
        return repository.deleteProductVariant(productId, variantId).toVertxVoidFuture()
    }

    override fun checkSkuExists(sku: String): Future<Boolean> {
        return repository.checkSkuExists(sku).toVertxFuture()
    }
}
