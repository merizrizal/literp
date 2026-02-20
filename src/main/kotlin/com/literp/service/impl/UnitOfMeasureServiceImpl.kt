package com.literp.service.impl

import com.literp.repository.UnitOfMeasureRepository
import com.literp.service.UnitOfMeasureService
import io.vertx.core.Future
import io.vertx.core.json.JsonObject

class UnitOfMeasureServiceImpl(
    private val repository: UnitOfMeasureRepository
) : UnitOfMeasureService {

    override fun listUnitOfMeasures(page: Int, size: Int, sort: String): Future<JsonObject> {
        return repository.listUnitOfMeasures(page, size, sort).toVertxFuture()
    }

    override fun createUnitOfMeasure(code: String, name: String, baseUnit: String?): Future<JsonObject> {
        return repository.createUnitOfMeasure(code, name, baseUnit).toVertxFuture()
    }

    override fun getUnitOfMeasure(uomId: String): Future<JsonObject> {
        return repository.getUnitOfMeasure(uomId).toVertxFuture()
    }

    override fun updateUnitOfMeasure(uomId: String, name: String, baseUnit: String?): Future<JsonObject> {
        return repository.updateUnitOfMeasure(uomId, name, baseUnit).toVertxFuture()
    }

    override fun deleteUnitOfMeasure(uomId: String): Future<Void> {
        return repository.deleteUnitOfMeasure(uomId).toVertxVoidFuture()
    }

    override fun checkCodeExists(code: String): Future<Boolean> {
        return repository.checkCodeExists(code).toVertxFuture()
    }
}
