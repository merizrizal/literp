package com.literp.verticle.handler

import io.vertx.rxjava3.ext.web.RoutingContext

class PosOperationsHandler : BaseHandler(PosOperationsHandler::class.java) {
    fun listPosTerminals(context: RoutingContext) {
        respondNotImplemented(context)
    }

    fun createPosTerminal(context: RoutingContext) {
        respondNotImplemented(context)
    }

    fun getPosTerminal(context: RoutingContext) {
        respondNotImplemented(context)
    }

    fun updatePosTerminal(context: RoutingContext) {
        respondNotImplemented(context)
    }

    fun deactivatePosTerminal(context: RoutingContext) {
        respondNotImplemented(context)
    }

    fun openPosShift(context: RoutingContext) {
        respondNotImplemented(context)
    }

    fun getCurrentPosShift(context: RoutingContext) {
        respondNotImplemented(context)
    }

    fun closePosShift(context: RoutingContext) {
        respondNotImplemented(context)
    }

    fun getPosReceiptByNumber(context: RoutingContext) {
        respondNotImplemented(context)
    }

    fun listPosReceiptsBySalesOrder(context: RoutingContext) {
        respondNotImplemented(context)
    }

    private fun respondNotImplemented(context: RoutingContext) {
        putErrorResponse(
            context = context,
            statusCode = 501,
            message = "POS operation is not implemented",
            errorCode = NOT_IMPLEMENTED_ERROR_CODE
        )
    }

    private companion object {
        const val NOT_IMPLEMENTED_ERROR_CODE = "NOT_IMPLEMENTED"
    }
}
