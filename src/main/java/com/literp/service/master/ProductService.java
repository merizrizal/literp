package com.literp.service.master;

import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.codegen.annotations.VertxGen;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.serviceproxy.ServiceBinder;

@ProxyGen
@VertxGen
public interface ProductService {
    String ADDRESS = "service.master.product";

    Future<JsonObject> listProducts(int page, int size, String sort, String sku, String productType, boolean activeOnly);

    Future<JsonObject> createProduct(String sku, String name, String productType, String baseUom, boolean active, JsonObject metadata);

    Future<JsonObject> getProduct(String productId, boolean includeVariants);

    Future<JsonObject> updateProduct(String productId, String name, String productType, String baseUom, Boolean active, JsonObject metadata);

    Future<Void> deleteProduct(String productId);

    Future<Boolean> checkSkuExists(String sku);

    static ProductService createProxy(Vertx vertx) {
        return new ProductServiceVertxEBProxy(vertx, ADDRESS);
    }

    static void register(Vertx vertx, ProductService service) {
        new ServiceBinder(vertx).setAddress(ADDRESS).register(ProductService.class, service);
    }
}
