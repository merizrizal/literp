package com.literp.service;

import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.codegen.annotations.VertxGen;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.serviceproxy.ServiceBinder;

@ProxyGen
@VertxGen
public interface LocationService {
    String ADDRESS = "service.location";

    Future<JsonObject> listLocations(
        int page,
        int size,
        String sort,
        String code,
        String name,
        String locationType,
        boolean activeOnly
    );

    Future<JsonObject> createLocation(String code, String name, String locationType, JsonObject address);

    Future<JsonObject> getLocation(String locationId);

    Future<JsonObject> getLocationByCode(String code);

    Future<JsonObject> updateLocation(String locationId, String name, String locationType, JsonObject address);

    Future<Void> deleteLocation(String locationId);

    Future<Boolean> checkCodeExists(String code);

    static LocationService createProxy(Vertx vertx) {
        return new LocationServiceVertxEBProxy(vertx, ADDRESS);
    }

    static void register(Vertx vertx, LocationService service) {
        new ServiceBinder(vertx).setAddress(ADDRESS).register(LocationService.class, service);
    }
}
