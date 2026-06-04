package com.literp.config

import java.io.File
import java.io.FileInputStream
import java.util.*

class Config {

    val httpPort: Int
    val pgHost: String
    val pgPort: Int
    val pgUser: String
    val pgPassword: String
    val pgDatabase: String

    init {
        val props = Properties()
        val configFile = File(CONFIG_FILE)
        val fileLoaded = configFile.exists()

        if (fileLoaded) {
            FileInputStream(configFile).use { props.load(it) }
        }

        val values = REQUIRED_CONFIG.associateWith { spec -> resolveValue(props, spec) }
        val missing = values.filterValues { it.isNullOrBlank() }.keys

        if (missing.isNotEmpty()) {
            throw IllegalStateException(missingConfigMessage(fileLoaded, missing))
        }

        httpPort = parseInt(HTTP_PORT, values.getValue(HTTP_PORT)!!)
        pgHost = values.getValue(PG_HOST)!!
        pgPort = parseInt(PG_PORT, values.getValue(PG_PORT)!!)
        pgUser = values.getValue(PG_USER)!!
        pgPassword = values.getValue(PG_PASSWORD)!!
        pgDatabase = values.getValue(PG_DATABASE)!!
    }

    private fun resolveValue(props: Properties, spec: ConfigSpec): String? {
        spec.envNames.forEach { envName ->
            val envValue = System.getenv(envName)
            if (!envValue.isNullOrBlank()) {
                return envValue.trim()
            }
        }

        return props.getProperty(spec.propertyName)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun parseInt(spec: ConfigSpec, value: String): Int {
        return value.toIntOrNull()
            ?: throw IllegalStateException(
                "Invalid integer config '${spec.propertyName}' with value '$value'. " +
                    "Set a numeric value in $CONFIG_FILE or one of: ${spec.envNames.joinToString(", ")}"
            )
    }

    private fun missingConfigMessage(fileLoaded: Boolean, missing: Set<ConfigSpec>): String {
        val fileState = if (fileLoaded) {
            "$CONFIG_FILE is present but incomplete"
        } else {
            "$CONFIG_FILE was not found"
        }
        val missingValues = missing.joinToString("; ") { spec ->
            "${spec.propertyName} (env: ${spec.envNames.joinToString(", ")})"
        }
        return "$fileState. Missing required config: $missingValues"
    }

    companion object {
        private const val CONFIG_FILE = "cfg.properties"

        private val HTTP_PORT = ConfigSpec("http.port", listOf("LITERP_HTTP_PORT", "HTTP_PORT"))
        private val PG_HOST = ConfigSpec("pg.host", listOf("LITERP_PG_HOST", "PG_HOST", "DB_HOST"))
        private val PG_PORT = ConfigSpec("pg.port", listOf("LITERP_PG_PORT", "PG_PORT", "DB_PORT"))
        private val PG_USER = ConfigSpec("pg.user", listOf("LITERP_PG_USER", "PG_USER", "DB_USER"))
        private val PG_PASSWORD = ConfigSpec("pg.password", listOf("LITERP_PG_PASSWORD", "PG_PASSWORD", "DB_PASSWORD"))
        private val PG_DATABASE = ConfigSpec("pg.database", listOf("LITERP_PG_DATABASE", "PG_DATABASE", "DB_NAME"))

        private val REQUIRED_CONFIG = listOf(
            HTTP_PORT,
            PG_HOST,
            PG_PORT,
            PG_USER,
            PG_PASSWORD,
            PG_DATABASE
        )
    }
}

private data class ConfigSpec(
    val propertyName: String,
    val envNames: List<String>
)
