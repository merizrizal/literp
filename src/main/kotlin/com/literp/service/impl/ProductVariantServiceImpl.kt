package com.literp.service.impl

import com.literp.repository.ProductVariantRepository
import com.literp.service.ProductVariantService
import io.vertx.core.Future
import io.vertx.core.json.JsonObject

class ProductVariantServiceImpl(
    private val repository: ProductVariantRepository
) : ProductVariantService {

    override fun listProductVariants(productId: String, page: Int, size: Int, sort: String): Future<JsonObject> {
        return repository.listProductVariants(productId, page, size, sort).toVertxFuture()
    }

    override fun createProductVariant(
        productId: String,
        sku: String,
        name: String,
        attributes: JsonObject?
    ): Future<JsonObject> {
        return repository.createProductVariant(productId, sku, name, attributes).toVertxFuture()
    }

    override fun getProductVariant(productId: String, variantId: String): Future<JsonObject> {
        return repository.getProductVariant(productId, variantId).toVertxFuture()
    }

    override fun updateProductVariant(variantId: String, name: String, attributes: JsonObject?): Future<JsonObject> {
        return repository.updateProductVariant(variantId, name, attributes).toVertxFuture()
    }

    override fun deleteProductVariant(variantId: String): Future<Void> {
        return repository.deleteProductVariant(variantId).toVertxVoidFuture()
    }

    override fun checkSkuExists(sku: String): Future<Boolean> {
        return repository.checkSkuExists(sku).toVertxFuture()
    }
}
