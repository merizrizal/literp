package com.literp.service.order.impl

import com.literp.repository.OrderProcessRepository
import com.literp.service.order.OrderProcessService
import com.literp.service.toVertxFuture
import io.vertx.core.Future
import io.vertx.core.json.JsonObject
import java.math.BigDecimal

class OrderProcessServiceImpl(
    private val repository: OrderProcessRepository
) : OrderProcessService {

    override fun listSalesOrders(
        page: Int,
        size: Int,
        sort: String,
        status: String?,
        salesChannel: String?,
        locationId: String?
    ): Future<JsonObject> {
        return repository.listSalesOrders(page, size, sort, status, salesChannel, locationId).toVertxFuture()
    }

    override fun createSalesOrderDraft(
        salesChannel: String,
        locationId: String,
        customerId: String?,
        currency: String,
        notes: String?
    ): Future<JsonObject> {
        return repository
            .createSalesOrderDraft(salesChannel, locationId, customerId, currency, notes)
            .toVertxFuture()
    }

    override fun getSalesOrder(salesOrderId: String): Future<JsonObject> {
        return repository.getSalesOrder(salesOrderId).toVertxFuture()
    }

    override fun addSalesOrderLine(
        salesOrderId: String,
        productId: String,
        sku: String?,
        quantityOrdered: String,
        unitPrice: String
    ): Future<JsonObject> {
        return repository
            .addSalesOrderLine(
                salesOrderId,
                productId,
                sku,
                BigDecimal(quantityOrdered),
                BigDecimal(unitPrice)
            )
            .toVertxFuture()
    }

    override fun confirmSalesOrder(salesOrderId: String): Future<JsonObject> {
        return repository.confirmSalesOrder(salesOrderId).toVertxFuture()
    }

    override fun capturePayment(
        salesOrderId: String,
        paymentMethod: String,
        amount: String,
        transactionRef: String?
    ): Future<JsonObject> {
        return repository.capturePayment(salesOrderId, paymentMethod, BigDecimal(amount), transactionRef).toVertxFuture()
    }

    override fun fulfillSalesOrder(salesOrderId: String, createdBy: String?, notes: String?): Future<JsonObject> {
        return repository.fulfillSalesOrder(salesOrderId, createdBy, notes).toVertxFuture()
    }

    override fun cancelSalesOrder(salesOrderId: String, reason: String?): Future<JsonObject> {
        return repository.cancelSalesOrder(salesOrderId, reason).toVertxFuture()
    }
}
