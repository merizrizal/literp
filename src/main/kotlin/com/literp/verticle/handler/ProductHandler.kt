package com.literp.verticle.handler

import com.literp.service.ProductService
import com.literp.service.ProductVariantService
import io.vertx.core.Future
import io.vertx.core.json.JsonObject
import io.vertx.openapi.validation.ValidatedRequest
import io.vertx.rxjava3.ext.web.RoutingContext
import io.vertx.rxjava3.ext.web.openapi.router.RouterBuilder

class ProductHandler(
    private val productService: ProductService,
    private val variantService: ProductVariantService
) : BaseHandler(ProductHandler::class.java) {

    fun listProducts(context: RoutingContext) {
        val page = context.queryParam("page").firstOrNull()?.toIntOrNull() ?: 0
        val size = context.queryParam("size").firstOrNull()?.toIntOrNull() ?: 20
        val sort = context.queryParam("sort").firstOrNull() ?: "sku,asc"

        productService.listProducts(page, size, sort)
            .onSuccess { result -> putResponse(context, 200, result) }
            .onFailure { error -> putErrorResponse(context, 500, "Failed to list products: ${error.message}") }
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

        productService.checkSkuExists(sku)
            .compose { exists ->
                if (exists) {
                    Future.failedFuture(Exception("Product SKU already exists"))
                } else {
                    productService.createProduct(sku, name, productType, baseUom, metadata)
                }
            }
            .onSuccess { result -> putResponse(context, 201, JsonObject().put("data", result)) }
            .onFailure { error ->
                if (error.message?.contains("already exists") == true) {
                    putErrorResponse(context, 409, error.message ?: "Conflict")
                } else {
                    putErrorResponse(context, 500, "Failed to create product: ${error.message}")
                }
            }
    }

    fun getProduct(context: RoutingContext) {
        val productId = context.pathParam("productId")

        productService.getProduct(productId)
            .onSuccess { result -> putResponse(context, 200, JsonObject().put("data", result)) }
            .onFailure { _ -> putErrorResponse(context, 404, "Product not found") }
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

        productService.updateProduct(productId, name, productType, metadata)
            .onSuccess { result -> putResponse(context, 200, JsonObject().put("data", result)) }
            .onFailure { _ -> putErrorResponse(context, 404, "Product not found") }
    }

    fun deleteProduct(context: RoutingContext) {
        val productId = context.pathParam("productId")

        productService.deleteProduct(productId)
            .onSuccess { context.response().setStatusCode(204).end() }
            .onFailure { error -> putErrorResponse(context, 500, "Failed to delete product: ${error.message}") }
    }

    // Product Variant handlers
    fun listProductVariants(context: RoutingContext) {
        val productId = context.pathParam("productId")
        val page = context.queryParam("page").firstOrNull()?.toIntOrNull() ?: 0
        val size = context.queryParam("size").firstOrNull()?.toIntOrNull() ?: 20
        val sort = context.queryParam("sort").firstOrNull() ?: "sku,asc"

        variantService.listProductVariants(productId, page, size, sort)
            .onSuccess { result -> putResponse(context, 200, result) }
            .onFailure { error -> putErrorResponse(context, 500, "Failed to list variants: ${error.message}") }
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

        variantService.checkSkuExists(sku)
            .compose { exists ->
                if (exists) {
                    Future.failedFuture(Exception("Variant SKU already exists"))
                } else {
                    variantService.createProductVariant(productId, sku, name, attributes)
                }
            }
            .onSuccess { result -> putResponse(context, 201, JsonObject().put("data", result)) }
            .onFailure { error ->
                if (error.message?.contains("already exists") == true) {
                    putErrorResponse(context, 409, error.message ?: "Conflict")
                } else {
                    putErrorResponse(context, 500, "Failed to create variant: ${error.message}")
                }
            }
    }

    fun getProductVariant(context: RoutingContext) {
        val productId = context.pathParam("productId")
        val variantId = context.pathParam("variantId")

        variantService.getProductVariant(productId, variantId)
            .onSuccess { result -> putResponse(context, 200, JsonObject().put("data", result)) }
            .onFailure { _ -> putErrorResponse(context, 404, "Product variant not found") }
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

        variantService.updateProductVariant(variantId, name, attributes)
            .onSuccess { result -> putResponse(context, 200, JsonObject().put("data", result)) }
            .onFailure { _ -> putErrorResponse(context, 404, "Product variant not found") }
    }

    fun deleteProductVariant(context: RoutingContext) {
        val variantId = context.pathParam("variantId")

        variantService.deleteProductVariant(variantId)
            .onSuccess { context.response().setStatusCode(204).end() }
            .onFailure { error -> putErrorResponse(context, 500, "Failed to delete variant: ${error.message}") }
    }

}
