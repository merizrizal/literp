package com.literp.service.master;

import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.codegen.annotations.VertxGen;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.serviceproxy.ServiceBinder;

@ProxyGen
@VertxGen
public interface ProductVariantService {
    String ADDRESS = "service.master.productVariant";

    Future<JsonObject> listProductVariants(String productId, int page, int size, String sort, boolean activeOnly);

    Future<JsonObject> createProductVariant(String productId, String sku, String name, boolean active, JsonObject attributes);

    Future<JsonObject> getProductVariant(String productId, String variantId);

    Future<JsonObject> updateProductVariant(String productId, String variantId, String name, Boolean active, JsonObject attributes);

    Future<Void> deleteProductVariant(String productId, String variantId);

    Future<Boolean> checkSkuExists(String sku);

    static ProductVariantService createProxy(Vertx vertx) {
        return new ProductVariantServiceVertxEBProxy(vertx, ADDRESS);
    }

    static void register(Vertx vertx, ProductVariantService service) {
        new ServiceBinder(vertx).setAddress(ADDRESS).register(ProductVariantService.class, service);
    }
}
