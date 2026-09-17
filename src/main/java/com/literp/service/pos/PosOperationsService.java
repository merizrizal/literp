package com.literp.service.pos;

import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.codegen.annotations.VertxGen;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.serviceproxy.ServiceBinder;

@ProxyGen
@VertxGen
public interface PosOperationsService {
    String ADDRESS = "service.pos.operations";

    Future<JsonObject> listPosTerminals(
        int page,
        int size,
        String sort,
        String locationId,
        Boolean isActive,
        JsonArray authorizedLocationIds
    );

    Future<JsonObject> getPosTerminal(String terminalId, JsonArray authorizedLocationIds);

    static PosOperationsService createProxy(Vertx vertx) {
        return new PosOperationsServiceVertxEBProxy(vertx, ADDRESS);
    }

    static void register(Vertx vertx, PosOperationsService service) {
        new ServiceBinder(vertx).setAddress(ADDRESS).register(PosOperationsService.class, service);
    }
}
