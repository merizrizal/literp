package com.literp.verticle.handler

import com.literp.repository.ProductRepository
import com.literp.repository.ProductVariantRepository
import io.reactivex.rxjava3.core.Single
import io.vertx.core.json.JsonObject
import io.vertx.openapi.validation.ValidatedRequest
import io.vertx.rxjava3.ext.web.RoutingContext
import io.vertx.rxjava3.ext.web.openapi.router.RouterBuilder

class ProductHandler(
    private val productRepository: ProductRepository,
    private val variantRepository: ProductVariantRepository
) : BaseHandler(ProductHandler::class.java) {

    fun listProducts(context: RoutingContext) {
        val page = context.queryParam("page").firstOrNull()?.toIntOrNull() ?: 0
        val size = context.queryParam("size").firstOrNull()?.toIntOrNull() ?: 20
        val sort = context.queryParam("sort").firstOrNull() ?: "sku,asc"

        productRepository.listProducts(page, size, sort)
            .subscribe(
                { result -> putResponse(context, 200, result) },
                { error -> putErrorResponse(context, 500, "Failed to list products: ${error.message}") }
            )
    }

    fun createProduct(context: RoutingContext) {
        val validatedRequest: ValidatedRequest = context.get(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST)
        val body = validatedRequest.body.jsonObject
        val sku = body.getString("sku")
        val name = body.getString("name")
        val productType = body.getString("productType")
        val baseUom = body.getString("baseUom")
        val metadata = body.getJsonObject("metadata")

        if (sku.isNullOrEmpty() || name.isNullOrEmpty() || productType.isNullOrEmpty() || baseUom.isNullOrEmpty()) {
            putErrorResponse(context, 400, "sku, name, productType, and baseUom are required")
            return
        }

        productRepository.checkSkuExists(sku)
            .flatMap { exists ->
                if (exists) Single.error(Exception("Product SKU already exists"))
                else productRepository.createProduct(sku, name, productType, baseUom, metadata)
            }
            .subscribe(
                { result -> putResponse(context, 201, JsonObject().put("data", result)) },
                { error ->
                    if (error.message?.contains("already exists") == true) {
                        putErrorResponse(context, 409, error.message ?: "Conflict")
                    } else {
                        putErrorResponse(context, 500, "Failed to create product: ${error.message}")
                    }
                }
            )
    }

    fun getProduct(context: RoutingContext) {
        val productId = context.pathParam("productId")

        productRepository.getProduct(productId)
            .subscribe(
                { result -> putResponse(context, 200, JsonObject().put("data", result)) },
                { _ -> putErrorResponse(context, 404, "Product not found") }
            )
    }

    fun updateProduct(context: RoutingContext) {
        val productId = context.pathParam("productId")
        val validatedRequest: ValidatedRequest = context.get(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST)
        val body = validatedRequest.body.jsonObject
        val name = body.getString("name")
        val productType = body.getString("productType")
        val metadata = body.getJsonObject("metadata")

        if (name.isNullOrEmpty() || productType.isNullOrEmpty()) {
            putErrorResponse(context, 400, "name and productType are required")
            return
        }

        productRepository.updateProduct(productId, name, productType, metadata)
            .subscribe(
                { result -> putResponse(context, 200, JsonObject().put("data", result)) },
                { _ -> putErrorResponse(context, 404, "Product not found") }
            )
    }

    fun deleteProduct(context: RoutingContext) {
        val productId = context.pathParam("productId")

        productRepository.deleteProduct(productId)
            .subscribe(
                { context.response().setStatusCode(204).end() },
                { error -> putErrorResponse(context, 500, "Failed to delete product: ${error.message}" ) }
            )
    }

    // Product Variant handlers
    fun listProductVariants(context: RoutingContext) {
        val productId = context.pathParam("productId")
        val page = context.queryParam("page").firstOrNull()?.toIntOrNull() ?: 0
        val size = context.queryParam("size").firstOrNull()?.toIntOrNull() ?: 20
        val sort = context.queryParam("sort").firstOrNull() ?: "sku,asc"

        variantRepository.listProductVariants(productId, page, size, sort)
            .subscribe(
                { result -> putResponse(context, 200, result) },
                { error -> putErrorResponse(context, 500, "Failed to list variants: ${error.message}") }
            )
    }

    fun createProductVariant(context: RoutingContext) {
        val productId = context.pathParam("productId")
        val validatedRequest: ValidatedRequest = context.get(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST)
        val body = validatedRequest.body.jsonObject
        val sku = body.getString("sku")
        val name = body.getString("name")
        val attributes = body.getJsonObject("attributes")

        if (sku.isNullOrEmpty() || name.isNullOrEmpty()) {
            putErrorResponse(context, 400, "sku and name are required")
            return
        }

        variantRepository.checkSkuExists(sku)
            .flatMap { exists ->
                if (exists) Single.error(Exception("Variant SKU already exists"))
                else variantRepository.createProductVariant(productId, sku, name, attributes)
            }
            .subscribe(
                { result -> putResponse(context, 201, JsonObject().put("data", result)) },
                { error ->
                    if (error.message?.contains("already exists") == true) {
                        putErrorResponse(context, 409, error.message ?: "Conflict")
                    } else {
                        putErrorResponse(context, 500, "Failed to create variant: ${error.message}")
                    }
                }
            )
    }

    fun getProductVariant(context: RoutingContext) {
        val productId = context.pathParam("productId")
        val variantId = context.pathParam("variantId")

        variantRepository.getProductVariant(productId, variantId)
            .subscribe(
                { result -> putResponse(context, 200, JsonObject().put("data", result)) },
                { _ -> putErrorResponse(context, 404, "Product variant not found") }
            )
    }

    fun updateProductVariant(context: RoutingContext) {
        val variantId = context.pathParam("variantId")
        val body = context.body().asJsonObject()
        val name = body.getString("name")
        val attributes = body.getJsonObject("attributes")

        if (name.isNullOrEmpty()) {
            putErrorResponse(context, 400, "name is required")
            return
        }

        variantRepository.updateProductVariant(variantId, name, attributes)
            .subscribe(
                { result -> putResponse(context, 200, JsonObject().put("data", result)) },
                { _ -> putErrorResponse(context, 404, "Product variant not found") }
            )
    }

    fun deleteProductVariant(context: RoutingContext) {
        val variantId = context.pathParam("variantId")

        variantRepository.deleteProductVariant(variantId)
            .subscribe(
                { context.response().setStatusCode(204).end() },
                { error -> putErrorResponse(context, 500, "Failed to delete variant: ${error.message}") }
            )
    }

}
