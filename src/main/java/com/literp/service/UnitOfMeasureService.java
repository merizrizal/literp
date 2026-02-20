package com.literp.service;

import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.codegen.annotations.VertxGen;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.serviceproxy.ServiceBinder;

@ProxyGen
@VertxGen
public interface UnitOfMeasureService {
    String ADDRESS = "service.uom";

    Future<JsonObject> listUnitOfMeasures(int page, int size, String sort);

    Future<JsonObject> createUnitOfMeasure(String code, String name, String baseUnit);

    Future<JsonObject> getUnitOfMeasure(String uomId);

    Future<JsonObject> updateUnitOfMeasure(String uomId, String name, String baseUnit);

    Future<Void> deleteUnitOfMeasure(String uomId);

    Future<Boolean> checkCodeExists(String code);

    static UnitOfMeasureService createProxy(Vertx vertx) {
        return new UnitOfMeasureServiceVertxEBProxy(vertx, ADDRESS);
    }

    static void register(Vertx vertx, UnitOfMeasureService service) {
        new ServiceBinder(vertx).setAddress(ADDRESS).register(UnitOfMeasureService.class, service);
    }
}
