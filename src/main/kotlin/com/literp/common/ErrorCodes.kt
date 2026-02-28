package com.literp.common

object ErrorCodes {
    const val VALIDATION_ERROR = "VALIDATION_ERROR"
    const val RESOURCE_NOT_FOUND = "RESOURCE_NOT_FOUND"
    const val CONFLICT = "CONFLICT"
    const val DB_TIMEOUT = "DB_TIMEOUT"
    const val INTERNAL_ERROR = "INTERNAL_ERROR"

    fun fromStatus(statusCode: Int): String {
        return when (statusCode) {
            400 -> VALIDATION_ERROR
            404 -> RESOURCE_NOT_FOUND
            409 -> CONFLICT
            else -> INTERNAL_ERROR
        }
    }
}
