package com.literp.security

import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.auth.JWTOptions
import io.vertx.ext.auth.authentication.TokenCredentials
import io.vertx.ext.auth.jwt.JWTAuth
import io.vertx.ext.auth.jwt.JWTAuthOptions
import java.math.BigDecimal
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.Base64
import java.util.UUID

private const val MAX_TOKEN_BYTES = 16 * 1024
private const val MAX_JWKS_BYTES = 1024 * 1024
private const val MAX_CLAIM_STRING_LENGTH = 255
private const val MAX_ISSUER_LENGTH = 2_048
private const val MAX_LOCATION_GRANTS = 256
private const val CLOCK_SKEW_SECONDS = 30L
private const val MAX_TOKEN_LIFETIME_SECONDS = 5 * 60L
private val BASE64_URL_SEGMENT = Regex("[A-Za-z0-9_-]+")
private val PRIVATE_JWK_MEMBERS = setOf("d", "p", "q", "dp", "dq", "qi", "oth")
private val EMBEDDED_KEY_MEMBERS = setOf("jku", "jwk", "x5u", "x5c")

/** Required deployment identity settings. No development defaults are provided. */
data class SecurityConfig(
    val issuer: String,
    val audience: String,
    val organizationId: String,
    val jwksPath: Path
) {
    init {
        require(isHttpsIssuer(issuer) && issuer.length <= MAX_ISSUER_LENGTH) {
            "issuer must be an HTTPS URL without query or fragment"
        }
        require(audience.isNotBlank() && audience.length <= MAX_CLAIM_STRING_LENGTH) {
            "audience must be a non-blank bounded string"
        }
        require(organizationId.isNotBlank() && organizationId.length <= MAX_CLAIM_STRING_LENGTH) {
            "organizationId must be a non-blank bounded string"
        }
    }

    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): SecurityConfig {
            fun required(name: String): String =
                environment[name]?.trim()?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Missing required authentication setting: $name")

            return SecurityConfig(
                issuer = required("LITERP_AUTH_ISSUER"),
                audience = required("LITERP_AUTH_AUDIENCE"),
                organizationId = required("LITERP_AUTH_ORGANIZATION_ID"),
                jwksPath = Path.of(required("LITERP_AUTH_JWKS_PATH"))
            )
        }
    }
}

class InvalidCredential : CredentialFailure(
    CredentialFailureReason.INVALID_CREDENTIAL,
    "Invalid bearer credential"
)

/** Verifies an RS256 bearer token against a locally provisioned public JWKS. */
class JwtCredentialVerifier(
    vertx: Vertx,
    private val config: SecurityConfig,
    private val clock: Clock = Clock.systemUTC()
) : CredentialVerifier {
    private val keyIds: Set<String>
    private val jwtAuth: JWTAuth

    init {
        val jwks = loadJwks(config.jwksPath)
        keyIds = jwks.keyIds
        jwtAuth = try {
            JWTAuth.create(
                vertx,
                JWTAuthOptions()
                    .setJwks(jwks.keys)
                    .setJWTOptions(
                        JWTOptions()
                            .setAlgorithm("RS256")
                            .setIssuer(config.issuer)
                            .addAudience(config.audience)
                            .setLeeway(CLOCK_SKEW_SECONDS.toInt())
                    )
            )
        } catch (failure: RuntimeException) {
            throw IllegalStateException("Invalid configured authentication JWKS", failure)
        }
    }

    override fun verify(rawBearerCredential: String): Future<AuthenticatedPrincipal> {
        val header = try {
            parseAndValidateHeader(rawBearerCredential)
        } catch (failure: InvalidCredential) {
            return Future.failedFuture(failure)
        } catch (_: RuntimeException) {
            return Future.failedFuture(InvalidCredential())
        }

        val authentication = try {
            jwtAuth.authenticate(TokenCredentials(rawBearerCredential))
        } catch (_: RuntimeException) {
            return Future.failedFuture(InvalidCredential())
        }

        return authentication
            .map { user -> normalizeVerifiedClaims(user.attributes().getValue("accessToken"), header) }
            .recover { Future.failedFuture<AuthenticatedPrincipal>(InvalidCredential()) }
    }

    private fun parseAndValidateHeader(token: String): JsonObject {
        if (token.toByteArray(StandardCharsets.UTF_8).size > MAX_TOKEN_BYTES || token.any { it.code > 127 }) {
            throw InvalidCredential()
        }

        val segments = token.split(".", ignoreCase = false, limit = 4)
        if (segments.size != 3 || segments.any { !BASE64_URL_SEGMENT.matches(it) }) {
            throw InvalidCredential()
        }

        val header = decodeJsonObject(segments[0])
        decodeJsonObject(segments[1])

        if (header.getValue("alg") != "RS256" || header.getValue("typ") != "Bearer") {
            throw InvalidCredential()
        }

        val kid = header.getValue("kid")
        if (kid !is String || kid.isBlank() || kid.length > MAX_CLAIM_STRING_LENGTH || kid !in keyIds) {
            throw InvalidCredential()
        }
        if (header.fieldNames().any { it in EMBEDDED_KEY_MEMBERS }) {
            throw InvalidCredential()
        }

        return header
    }

    private fun normalizeVerifiedClaims(rawClaims: Any?, header: JsonObject): AuthenticatedPrincipal {
        val claims = rawClaims as? JsonObject ?: throw InvalidCredential()

        val issuer = stringClaim(claims, "iss", MAX_ISSUER_LENGTH)
        if (issuer != config.issuer) {
            throw InvalidCredential()
        }

        val subject = stringClaim(claims, "sub", MAX_CLAIM_STRING_LENGTH)
        val organizationId = stringClaim(claims, "literp_org", MAX_CLAIM_STRING_LENGTH)
        val issuedAt = epochSecondsClaim(claims, "iat")
        val expiresAt = epochSecondsClaim(claims, "exp")
        val notBefore = optionalEpochSecondsClaim(claims, "nbf")

        if (!audienceMatches(claims.getValue("aud")) || issuedAt < 0 || expiresAt < 0 ||
            (notBefore != null && notBefore < 0)
        ) {
            throw InvalidCredential()
        }

        if (expiresAt <= issuedAt || expiresAt - issuedAt > MAX_TOKEN_LIFETIME_SECONDS) {
            throw InvalidCredential()
        }

        val now = clock.instant().epochSecond
        if (issuedAt > now + CLOCK_SKEW_SECONDS ||
            (notBefore != null && notBefore > now + CLOCK_SKEW_SECONDS) ||
            expiresAt <= now - CLOCK_SKEW_SECONDS
        ) {
            throw InvalidCredential()
        }

        val capabilities = stringSetClaim(claims, "literp_permissions", null)
        val locationIds = stringSetClaim(claims, "literp_locations", MAX_LOCATION_GRANTS) { location ->
            val uuid = runCatching { UUID.fromString(location) }.getOrNull()
            uuid != null && uuid.toString() == location
        }

        val principalKind = when (claims.getValue("literp_kind")) {
            "human" -> PrincipalKind.HUMAN
            "service" -> PrincipalKind.SERVICE
            else -> throw InvalidCredential()
        }

        val operator = if (!claims.containsKey("literp_operator")) {
            false
        } else {
            claims.getValue("literp_operator") as? Boolean ?: throw InvalidCredential()
        }

        if (header.getValue("kid") !in keyIds) {
            throw InvalidCredential()
        }

        return AuthenticatedPrincipal(
            subject = subject,
            issuer = issuer,
            organizationId = organizationId,
            capabilities = capabilities,
            locationIds = locationIds,
            principalKind = principalKind,
            operator = operator,
            expiresAt = java.time.Instant.ofEpochSecond(expiresAt)
        )
    }

    private fun stringClaim(claims: JsonObject, name: String, maxLength: Int): String {
        val value = claims.getValue(name)
        if (value !is String || value.isBlank() || value.length > maxLength) {
            throw InvalidCredential()
        }
        return value
    }

    private fun epochSecondsClaim(claims: JsonObject, name: String): Long =
        if (claims.containsKey(name)) {
            epochSeconds(claims.getValue(name))
        } else {
            throw InvalidCredential()
        }

    private fun optionalEpochSecondsClaim(claims: JsonObject, name: String): Long? =
        if (claims.containsKey(name)) epochSeconds(claims.getValue(name)) else null

    private fun epochSeconds(value: Any?): Long {
        if (value !is Number) {
            throw InvalidCredential()
        }

        return try {
            BigDecimal(value.toString()).toBigIntegerExact().longValueExact()
        } catch (_: ArithmeticException) {
            throw InvalidCredential()
        } catch (_: NumberFormatException) {
            throw InvalidCredential()
        }
    }

    private fun audienceMatches(value: Any?): Boolean = when (value) {
        is String -> value == config.audience
        is JsonArray -> value.list.all { it is String } && config.audience in value.list
        else -> false
    }

    private fun stringSetClaim(
        claims: JsonObject,
        name: String,
        maxEntries: Int?,
        predicate: (String) -> Boolean = { true }
    ): Set<String> {
        if (!claims.containsKey(name)) {
            return emptySet()
        }

        val value = claims.getValue(name) as? JsonArray ?: throw InvalidCredential()
        if (maxEntries != null && value.size() > maxEntries) {
            throw InvalidCredential()
        }

        return value.list.map { entry ->
            if (entry !is String || entry.isBlank() || entry.length > MAX_CLAIM_STRING_LENGTH || !predicate(entry)) {
                throw InvalidCredential()
            }
            entry
        }.toSet()
    }

    private fun decodeJsonObject(segment: String): JsonObject {
        return try {
            JsonObject(String(Base64.getUrlDecoder().decode(segment), StandardCharsets.UTF_8))
        } catch (_: RuntimeException) {
            throw InvalidCredential()
        }
    }

    private data class LoadedJwks(
        val keys: List<JsonObject>,
        val keyIds: Set<String>
    )

    private fun loadJwks(path: Path): LoadedJwks {
        val normalizedPath = path.toAbsolutePath().normalize()
        try {
            if (!Files.isRegularFile(normalizedPath) || Files.size(normalizedPath) > MAX_JWKS_BYTES) {
                throw IllegalStateException("JWKS path is missing, not a regular file, or too large")
            }

            val bytes = Files.readAllBytes(normalizedPath)
            if (bytes.size > MAX_JWKS_BYTES) {
                throw IllegalStateException("JWKS file is too large")
            }

            val root = JsonObject(String(bytes, StandardCharsets.UTF_8))
            val keys = root.getValue("keys") as? JsonArray
                ?: throw IllegalStateException("JWKS must contain a keys array")
            if (keys.isEmpty) {
                throw IllegalStateException("JWKS must contain at least one key")
            }

            val seenKeyIds = mutableSetOf<String>()
            val publicKeys = (0 until keys.size()).map { index ->
                val key = try {
                    keys.getJsonObject(index)
                } catch (_: RuntimeException) {
                    null
                } ?: throw IllegalStateException("JWKS key must be an object")
                validateJwk(key, seenKeyIds)
                key
            }

            return LoadedJwks(publicKeys, seenKeyIds.toSet())
        } catch (failure: IllegalStateException) {
            throw failure
        } catch (failure: Exception) {
            throw IllegalStateException("Unable to load configured authentication JWKS", failure)
        }
    }

    private fun validateJwk(key: JsonObject, seenKeyIds: MutableSet<String>) {
        if (key.getValue("kty") != "RSA" || key.getValue("alg") != "RS256") {
            throw IllegalStateException("Configured JWKS contains an unsupported key")
        }

        val use = key.getValue("use")
        if (use != null && use != "sig") {
            throw IllegalStateException("Configured JWKS key is not a signing key")
        }

        val kid = key.getValue("kid")
        if (kid !is String || kid.isBlank() || kid.length > MAX_CLAIM_STRING_LENGTH || !seenKeyIds.add(kid)) {
            throw IllegalStateException("Configured JWKS keys must have unique bounded kid values")
        }

        listOf("n", "e").forEach { member ->
            val value = key.getValue(member)
            if (value !is String || !BASE64_URL_SEGMENT.matches(value) || !isBase64Url(value)) {
                throw IllegalStateException("Configured JWKS RSA key is malformed")
            }
        }

        if (key.fieldNames().any { it in PRIVATE_JWK_MEMBERS || it in EMBEDDED_KEY_MEMBERS }) {
            throw IllegalStateException("Configured JWKS must contain public keys only")
        }

        val keyOperations = key.getValue("key_ops")
        if (keyOperations != null) {
            val operations = keyOperations as? JsonArray
                ?: throw IllegalStateException("Configured JWKS key_ops must be an array")
            if (operations.list.any { it !is String } || "verify" !in operations.list) {
                throw IllegalStateException("Configured JWKS key_ops must allow verification")
            }
        }
    }

    private fun isBase64Url(value: String): Boolean =
        runCatching { Base64.getUrlDecoder().decode(value) }.getOrNull()?.isNotEmpty() == true
}

private fun isHttpsIssuer(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.isAbsolute && uri.scheme.equals("https", ignoreCase = true) &&
        !uri.host.isNullOrBlank() && uri.query == null && uri.fragment == null
}.getOrDefault(false)
