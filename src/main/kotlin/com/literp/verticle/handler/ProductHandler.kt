package com.literp.verticle.handler

import com.literp.common.ErrorCodes
import com.literp.service.master.ProductService
import com.literp.service.master.ProductVariantService
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
            .onSuccess { result -> putSuccessResponse(context, 200, result) }
            .onFailure { error -> putErrorResponse(context, 500, "Failed to list products: ${error.message}", error) }
    }

    fun createProduct(context: RoutingContext) {
        val validatedRequest: ValidatedRequest = context.get(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST)
        val body = validatedRequest.body.jsonObject
        val sku = body?.getString("sku")
        val name = body?.getString("name")
        val productType = body?.getString("productType")
        val baseUom = body?.getString("baseUom")
        val metadata = body?.getJsonObject("metadata")

        if (sku.isNullOrEmpty() || name.isNullOrEmpty() || productType.isNullOrEmpty() || baseUom.isNullOrEmpty()) {
            putErrorResponse(context, 400, "sku, name, productType, and baseUom are required")
            return
        }

        productService.checkSkuExists(sku)
            .compose { exists ->
                if (exists) {
                    Future.failedFuture(Exception(ErrorCodes.fromStatus(409)))
                } else {
                    productService.createProduct(sku, name, productType, baseUom, metadata)
                }
            }
            .onSuccess { result -> putSuccessResponse(context, 201, JsonObject().put("data", result)) }
            .onFailure { error ->
                putMappedErrorResponse(
                    context = context,
                    error = error,
                    internalErrorMessage = "Failed to create product",
                    conflictMessage = "Product SKU already exists"
                )
            }
    }

    fun getProduct(context: RoutingContext) {
        val productId = context.pathParam("productId")

        productService.getProduct(productId)
            .onSuccess { result -> putSuccessResponse(context, 200, JsonObject().put("data", result)) }
            .onFailure { error ->
                putMappedErrorResponse(
                    context = context,
                    error = error,
                    internalErrorMessage = "Failed to get product",
                    notFoundMessage = "Product not found"
                )
            }
    }

    fun updateProduct(context: RoutingContext) {
        val productId = context.pathParam("productId")
        val validatedRequest: ValidatedRequest = context.get(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST)
        val body = validatedRequest.body.jsonObject
        val name = body?.getString("name")
        val productType = body?.getString("productType")
        val metadata = body?.getJsonObject("metadata")

        if (name.isNullOrEmpty() || productType.isNullOrEmpty()) {
            putErrorResponse(context, 400, "name and productType are required")
            return
        }

        productService.updateProduct(productId, name, productType, metadata)
            .onSuccess { result -> putSuccessResponse(context, 200, JsonObject().put("data", result)) }
            .onFailure { error ->
                putMappedErrorResponse(
                    context = context,
                    error = error,
                    internalErrorMessage = "Failed to update product",
                    notFoundMessage = "Product not found"
                )
            }
    }

    fun deleteProduct(context: RoutingContext) {
        val productId = context.pathParam("productId")

        productService.deleteProduct(productId)
            .onSuccess { context.response().setStatusCode(204).end() }
            .onFailure { error ->
                putMappedErrorResponse(
                    context = context,
                    error = error,
                    internalErrorMessage = "Failed to delete product",
                    notFoundMessage = "Product not found"
                )
            }
    }

    fun listProductVariants(context: RoutingContext) {
        val productId = context.pathParam("productId")
        val page = context.queryParam("page").firstOrNull()?.toIntOrNull() ?: 0
        val size = context.queryParam("size").firstOrNull()?.toIntOrNull() ?: 20
        val sort = context.queryParam("sort").firstOrNull() ?: "sku,asc"

        variantService.listProductVariants(productId, page, size, sort)
            .onSuccess { result -> putSuccessResponse(context, 200, result) }
            .onFailure { error ->
                putMappedErrorResponse(
                    context = context,
                    error = error,
                    internalErrorMessage = "Failed to list product variants",
                    notFoundMessage = "Product or variant not found"
                )
            }
    }

    fun createProductVariant(context: RoutingContext) {
        val productId = context.pathParam("productId")
        val validatedRequest: ValidatedRequest = context.get(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST)
        val body = validatedRequest.body.jsonObject
        val sku = body?.getString("sku")
        val name = body?.getString("name")
        val attributes = body?.getJsonObject("attributes")

        if (sku.isNullOrEmpty() || name.isNullOrEmpty()) {
            putErrorResponse(context, 400, "sku and name are required")
            return
        }

        variantService.checkSkuExists(sku)
            .compose { exists ->
                if (exists) {
                    Future.failedFuture(Exception(ErrorCodes.fromStatus(409)))
                } else {
                    variantService.createProductVariant(productId, sku, name, attributes)
                }
            }
            .onSuccess { result -> putSuccessResponse(context, 201, JsonObject().put("data", result)) }
            .onFailure { error ->
                putMappedErrorResponse(
                    context = context,
                    error = error,
                    internalErrorMessage = "Failed to create product variant",
                    notFoundMessage = "Product not found",
                    conflictMessage = "Variant SKU already exists"
                )
            }
    }

    fun getProductVariant(context: RoutingContext) {
        val productId = context.pathParam("productId")
        val variantId = context.pathParam("variantId")

        variantService.getProductVariant(productId, variantId)
            .onSuccess { result -> putSuccessResponse(context, 200, JsonObject().put("data", result)) }
            .onFailure { error ->
                putMappedErrorResponse(
                    context = context,
                    error = error,
                    internalErrorMessage = "Failed to get product variant",
                    notFoundMessage = "Product variant not found"
                )
            }
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
            .onSuccess { result -> putSuccessResponse(context, 200, JsonObject().put("data", result)) }
            .onFailure { error ->
                putMappedErrorResponse(
                    context = context,
                    error = error,
                    internalErrorMessage = "Failed to update product variant",
                    notFoundMessage = "Product variant not found"
                )
            }
    }

    fun deleteProductVariant(context: RoutingContext) {
        val variantId = context.pathParam("variantId")

        variantService.deleteProductVariant(variantId)
            .onSuccess { context.response().setStatusCode(204).end() }
            .onFailure { error ->
                putMappedErrorResponse(
                    context = context,
                    error = error,
                    internalErrorMessage = "Failed to delete product variant",
                    notFoundMessage = "Product variant not found"
                )
            }
    }
}
