package com.literp.service.pos.impl

import com.literp.repository.PosOperationsRepository
import com.literp.service.pos.PosOperationsService
import com.literp.service.toVertxFuture
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

class PosOperationsServiceImpl(
    private val repository: PosOperationsRepository
) : PosOperationsService {

    override fun listPosTerminals(
        page: Int,
        size: Int,
        sort: String,
        locationId: String?,
        isActive: Boolean?,
        authorizedLocationIds: JsonArray
    ): Future<JsonObject> = repository.listPosTerminals(
        page = page,
        size = size,
        sort = sort,
        locationId = locationId,
        isActive = isActive,
        authorizedLocationIds = authorizedLocationIds.toAuthorizedLocationIds()
    ).toVertxFuture()

    override fun getPosTerminal(
        terminalId: String,
        authorizedLocationIds: JsonArray
    ): Future<JsonObject> = repository.getPosTerminal(
        terminalId = terminalId,
        authorizedLocationIds = authorizedLocationIds.toAuthorizedLocationIds()
    ).toVertxFuture()

    override fun createPosTerminal(
        locationId: String,
        terminalCode: String,
        deviceName: String,
        idempotencyKey: String,
        actorSubject: String,
        organizationId: String,
        authorizedLocationIds: JsonArray
    ): Future<JsonObject> = repository.createPosTerminal(
        locationId = locationId,
        terminalCode = terminalCode,
        deviceName = deviceName,
        idempotencyKey = idempotencyKey,
        actorSubject = actorSubject,
        organizationId = organizationId,
        authorizedLocationIds = authorizedLocationIds.toAuthorizedLocationIds()
    ).toVertxFuture()

    override fun updatePosTerminal(
        terminalId: String,
        terminalCode: String?,
        deviceName: String?,
        authorizedLocationIds: JsonArray
    ): Future<JsonObject> = repository.updatePosTerminal(
        terminalId = terminalId,
        terminalCode = terminalCode,
        deviceName = deviceName,
        authorizedLocationIds = authorizedLocationIds.toAuthorizedLocationIds()
    ).toVertxFuture()

    override fun deactivatePosTerminal(
        terminalId: String,
        authorizedLocationIds: JsonArray
    ): Future<JsonObject> = repository.deactivatePosTerminal(
        terminalId = terminalId,
        authorizedLocationIds = authorizedLocationIds.toAuthorizedLocationIds()
    ).toVertxFuture()

    private fun JsonArray.toAuthorizedLocationIds(): Set<String> =
        (0 until size())
            .mapNotNull { getString(it)?.trim()?.takeIf(String::isNotBlank) }
            .toSet()
}
