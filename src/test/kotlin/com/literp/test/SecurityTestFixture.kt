package com.literp.test

import com.literp.security.ExplicitSecurityPolicy
import com.literp.security.JwtCredentialVerifier
import com.literp.security.SecurityConfig
import com.literp.verticle.handler.SecurityHandler
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64
import java.util.Comparator

class SecurityTestFixture(
    private val vertx: Vertx,
    val organizationId: String = "literp-org"
) : AutoCloseable {
    val issuer = "https://issuer.example/realm/literp"
    val audience = "literp-api"
    val locationId = "9b122b54-41f9-4e7a-aef6-26b1f196fe94"
    val securityHandler: SecurityHandler

    private val tempDirectory: Path = Files.createTempDirectory("literp-http-security-test-")
    private val jwksPath: Path = tempDirectory.resolve("jwks.json")
    private val signingKey: KeyPair = generateKeyPair()

    init {
        Files.writeString(
            jwksPath,
            JsonObject()
                .put("keys", JsonArray().add(publicJwk(signingKey, "test-key")))
                .encode(),
            StandardCharsets.UTF_8
        )
        val verifier = JwtCredentialVerifier(
            vertx,
            SecurityConfig(issuer, audience, organizationId, jwksPath)
        )
        securityHandler = SecurityHandler(verifier, ExplicitSecurityPolicy(organizationId))
    }

    fun authorization(
        capabilities: Set<String> = ALL_CAPABILITIES,
        locationIds: Set<String> = setOf(locationId),
        organizationId: String = this.organizationId,
        operator: Boolean = true,
        principalKind: String = "human",
        subject: String = "http-test-subject"
    ): Map<String, String> = mapOf(
        "Authorization" to "Bearer ${token(capabilities, locationIds, organizationId, operator, principalKind, subject)}"
    )

    fun token(
        capabilities: Set<String> = ALL_CAPABILITIES,
        locationIds: Set<String> = setOf(locationId),
        organizationId: String = this.organizationId,
        operator: Boolean = true,
        principalKind: String = "human",
        subject: String = "http-test-subject",
        expiresAt: Long = Instant.now().epochSecond + 300
    ): String {
        val now = Instant.now().epochSecond
        val claims = JsonObject()
            .put("iss", issuer)
            .put("sub", subject)
            .put("aud", audience)
            .put("iat", now)
            .put("exp", expiresAt)
            .put("literp_org", organizationId)
            .put("literp_permissions", JsonArray().apply { capabilities.forEach(::add) })
            .put("literp_locations", JsonArray().apply { locationIds.forEach(::add) })
            .put("literp_kind", principalKind)
            .put("literp_operator", operator)

        val header = JsonObject()
            .put("typ", "Bearer")
            .put("alg", "RS256")
            .put("kid", "test-key")
        val headerSegment = base64Url(header.encode().toByteArray(StandardCharsets.UTF_8))
        val payloadSegment = base64Url(claims.encode().toByteArray(StandardCharsets.UTF_8))
        val signingInput = "$headerSegment.$payloadSegment"
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(signingKey.private)
            update(signingInput.toByteArray(StandardCharsets.US_ASCII))
        }.sign()

        return "$signingInput.${base64Url(signature)}"
    }

    override fun close() {
        Files.walk(tempDirectory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun publicJwk(keyPair: KeyPair, kid: String): JsonObject {
        val publicKey = keyPair.public as RSAPublicKey
        return JsonObject()
            .put("kty", "RSA")
            .put("alg", "RS256")
            .put("use", "sig")
            .put("kid", kid)
            .put("n", base64Url(unsignedBytes(publicKey.modulus)))
            .put("e", base64Url(unsignedBytes(publicKey.publicExponent)))
    }

    private fun generateKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply {
        initialize(2048)
    }.generateKeyPair()

    private fun unsignedBytes(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return if (bytes.first() == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    companion object {
        val ALL_CAPABILITIES: Set<String> = setOf(
            "master-data.read",
            "master-data.write",
            "order.read",
            "order.write",
            "order.confirm",
            "payment.capture",
            "order.fulfill",
            "order.cancel",
            "inventory.read",
            "operations.read",
            "pos.terminal.read",
            "pos.terminal.write",
            "pos.shift.open",
            "pos.shift.read",
            "pos.shift.close",
            "pos.receipt.read"
        )
    }
}
