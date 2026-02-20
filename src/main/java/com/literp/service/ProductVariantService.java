package com.literp.service;

import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.codegen.annotations.VertxGen;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.serviceproxy.ServiceBinder;

@ProxyGen
@VertxGen
public interface ProductVariantService {
    String ADDRESS = "service.productVariant";

    Future<JsonObject> listProductVariants(String productId, int page, int size, String sort);

    Future<JsonObject> createProductVariant(String productId, String sku, String name, JsonObject attributes);

    Future<JsonObject> getProductVariant(String productId, String variantId);

    Future<JsonObject> updateProductVariant(String variantId, String name, JsonObject attributes);

    Future<Void> deleteProductVariant(String variantId);

    Future<Boolean> checkSkuExists(String sku);

    static ProductVariantService createProxy(Vertx vertx) {
        return new ProductVariantServiceVertxEBProxy(vertx, ADDRESS);
    }

    static void register(Vertx vertx, ProductVariantService service) {
        new ServiceBinder(vertx).setAddress(ADDRESS).register(ProductVariantService.class, service);
    }
}
