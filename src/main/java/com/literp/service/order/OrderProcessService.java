package com.literp.service.order;

import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.codegen.annotations.VertxGen;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.serviceproxy.ServiceBinder;

@ProxyGen
@VertxGen
public interface OrderProcessService {
    String ADDRESS = "service.order.process";

    Future<JsonObject> listSalesOrders(int page, int size, String sort, String status, String salesChannel, String locationId);

    Future<JsonObject> createSalesOrderDraft(String salesChannel, String locationId, String customerId, String currency, String notes);

    Future<JsonObject> getSalesOrder(String salesOrderId);

    Future<JsonObject> addSalesOrderLine(String salesOrderId, String productId, String sku, String quantityOrdered, String unitPrice);

    Future<JsonObject> confirmSalesOrder(String salesOrderId);

    Future<JsonObject> capturePayment(String salesOrderId, String paymentMethod, String amount, String transactionRef);

    Future<JsonObject> fulfillSalesOrder(String salesOrderId, String createdBy, String notes);

    Future<JsonObject> cancelSalesOrder(String salesOrderId, String reason);

    static OrderProcessService createProxy(Vertx vertx) {
        return new OrderProcessServiceVertxEBProxy(vertx, ADDRESS);
    }

    static void register(Vertx vertx, OrderProcessService service) {
        new ServiceBinder(vertx).setAddress(ADDRESS).register(OrderProcessService.class, service);
    }
}
