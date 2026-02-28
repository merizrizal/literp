package com.literp.verticle.handler

import com.literp.service.order.OrderProcessService
import io.vertx.openapi.validation.ValidatedRequest
import io.vertx.rxjava3.ext.web.RoutingContext
import io.vertx.rxjava3.ext.web.openapi.router.RouterBuilder

class OrderProcessHandler(
    private val orderService: OrderProcessService
) : BaseHandler(OrderProcessHandler::class.java) {

    fun listSalesOrders(context: RoutingContext) {
        val page = context.queryParam("page").firstOrNull()?.toIntOrNull() ?: 0
        val size = context.queryParam("size").firstOrNull()?.toIntOrNull() ?: 20
        val sort = context.queryParam("sort").firstOrNull() ?: "orderDate,desc"
        val status = context.queryParam("status").firstOrNull()
        val salesChannel = context.queryParam("salesChannel").firstOrNull()
        val locationId = context.queryParam("locationId").firstOrNull()

        orderService.listSalesOrders(page, size, sort, status, salesChannel, locationId)
            .onSuccess { result -> putSuccessResponse(context, 200, result) }
            .onFailure { error ->
                putMappedErrorResponse(
                    context = context,
                    error = error,
                    internalErrorMessage = "Failed to list sales orders"
                )
            }
    }

    fun createSalesOrderDraft(context: RoutingContext) {
        val validatedRequest: ValidatedRequest = context.get(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST)
        val body = validatedRequest.body?.jsonObject ?: context.body().asJsonObject()

        val salesChannel = body.getString("salesChannel") ?: "POS"
        val locationId = body.getString("locationId")
        val customerId = body.getString("customerId")
        val currency = body.getString("currency") ?: "USD"
        val notes = body.getString("notes")

        if (locationId.isNullOrBlank()) {
            putErrorResponse(context, 400, "locationId is required")
            return
        }

        orderService.createSalesOrderDraft(salesChannel, locationId, customerId, currency, notes)
            .onSuccess { result -> putSuccessResponse(context, 201, result) }
            .onFailure { error ->
                putMappedErrorResponse(
                    context = context,
                    error = error,
                    internalErrorMessage = "Failed to create sales order draft"
                )
            }
    }

    fun getSalesOrder(context: RoutingContext) {
        val orderId = context.pathParam("salesOrderId")
        orderService.getSalesOrder(orderId)
            .onSuccess { result -> putSuccessResponse(context, 200, result) }
            .onFailure { error ->
                putMappedErrorResponse(
                    context = context,
                    error = error,
                    internalErrorMessage = "Failed to get sales order",
                    notFoundMessage = "Sales order not found"
                )
            }
    }

    fun addSalesOrderLine(context: RoutingContext) {
        val orderId = context.pathParam("salesOrderId")
        val validatedRequest: ValidatedRequest = context.get(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST)
        val body = validatedRequest.body?.jsonObject ?: context.body().asJsonObject()

        val productId = body.getString("productId")
        val sku = body.getString("sku")
        val quantityOrdered = body.getValue("quantityOrdered")?.toString()
        val unitPrice = body.getValue("unitPrice")?.toString()

        if (productId.isNullOrBlank() || quantityOrdered.isNullOrBlank() || unitPrice.isNullOrBlank()) {
            putErrorResponse(context, 400, "productId, quantityOrdered, and unitPrice are required")
            return
        }

        orderService.addSalesOrderLine(orderId, productId, sku, quantityOrdered, unitPrice)
            .onSuccess { result -> putSuccessResponse(context, 201, result) }
            .onFailure { error ->
                putMappedErrorResponse(
                    context = context,
                    error = error,
                    internalErrorMessage = "Failed to add sales order line"
                )
            }
    }

    fun confirmSalesOrder(context: RoutingContext) {
        val orderId = context.pathParam("salesOrderId")
        orderService.confirmSalesOrder(orderId)
            .onSuccess { result -> putSuccessResponse(context, 200, result) }
            .onFailure { error ->
                putMappedErrorResponse(
                    context = context,
                    error = error,
                    internalErrorMessage = "Failed to confirm sales order"
                )
            }
    }

    fun capturePayment(context: RoutingContext) {
        val orderId = context.pathParam("salesOrderId")
        val validatedRequest: ValidatedRequest = context.get(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST)
        val body = validatedRequest.body?.jsonObject ?: context.body().asJsonObject()

        val paymentMethod = body.getString("paymentMethod")
        val amount = body.getValue("amount")?.toString()
        val transactionRef = body.getString("transactionRef")

        if (paymentMethod.isNullOrBlank() || amount.isNullOrBlank()) {
            putErrorResponse(context, 400, "paymentMethod and amount are required")
            return
        }

        orderService.capturePayment(orderId, paymentMethod, amount, transactionRef)
            .onSuccess { result -> putSuccessResponse(context, 201, result) }
            .onFailure { error ->
                putMappedErrorResponse(
                    context = context,
                    error = error,
                    internalErrorMessage = "Failed to capture payment"
                )
            }
    }

    fun fulfillSalesOrder(context: RoutingContext) {
        val orderId = context.pathParam("salesOrderId")
        val body = context.body().asJsonObject() ?: io.vertx.core.json.JsonObject()
        val createdBy = body.getString("createdBy")
        val notes = body.getString("notes")

        orderService.fulfillSalesOrder(orderId, createdBy, notes)
            .onSuccess { result -> putSuccessResponse(context, 200, result) }
            .onFailure { error ->
                putMappedErrorResponse(
                    context = context,
                    error = error,
                    internalErrorMessage = "Failed to fulfill sales order"
                )
            }
    }

    fun cancelSalesOrder(context: RoutingContext) {
        val orderId = context.pathParam("salesOrderId")
        val body = context.body().asJsonObject() ?: io.vertx.core.json.JsonObject()
        val reason = body.getString("reason")

        orderService.cancelSalesOrder(orderId, reason)
            .onSuccess { result -> putSuccessResponse(context, 200, result) }
            .onFailure { error ->
                putMappedErrorResponse(
                    context = context,
                    error = error,
                    internalErrorMessage = "Failed to cancel sales order"
                )
            }
    }
}
