package com.literp.security

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
import java.util.concurrent.ExecutionException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class JwtCredentialVerifierTest {
    private val issuer = "https://issuer.example/realm/literp"
    private val audience = "literp-api"
    private val organizationId = "organization-1"
    private val locationId = "9b122b54-41f9-4e7a-aef6-26b1f196fe94"

    private lateinit var vertx: Vertx
    private lateinit var tempDirectory: Path
    private lateinit var jwksPath: Path
    private lateinit var signingKey: KeyPair
    private lateinit var verifier: JwtCredentialVerifier

    @BeforeEach
    fun setUp() {
        vertx = Vertx.vertx()
        tempDirectory = Files.createTempDirectory("literp-jwt-test-")
        jwksPath = tempDirectory.resolve("jwks.json")
        signingKey = generateKeyPair()
        writeJwks(publicJwk(signingKey, "key-1"))
        verifier = newVerifier()
    }

    @AfterEach
    fun tearDown() {
        vertx.close().toCompletionStage().toCompletableFuture().get()
        Files.walk(tempDirectory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun verifiesAndNormalizesAValidAccessToken() {
        val principal = verify(verifier, signedToken())

        assertEquals("subject-1", principal.subject)
        assertEquals(issuer, principal.issuer)
        assertEquals(organizationId, principal.organizationId)
        assertEquals(setOf("master-data.read", "order.read"), principal.capabilities)
        assertEquals(setOf(locationId), principal.locationIds)
        assertEquals(PrincipalKind.HUMAN, principal.principalKind)
        assertTrue(principal.operator)
    }

    @Test
    fun rejectsAValidlyShapedTokenWithAForgedSignature() {
        val otherKey = generateKeyPair()

        assertRejected(verifier, signedToken(keyPair = otherKey))
    }

    @Test
    fun rejectsExpiredTokens() {
        val now = Instant.now().epochSecond
        val claims = validClaims()
            .put("iat", now - 600)
            .put("exp", now - 120)

        assertRejected(verifier, signedToken(claims = claims))
    }

    @Test
    fun rejectsNonBearerAndNonRs256Profiles() {
        assertRejected(verifier, signedToken(headerType = "JWT"))
        assertRejected(
            verifier,
            signedToken(algorithm = "RS384", signatureAlgorithm = "SHA384withRSA")
        )
    }

    @Test
    fun rejectsMalformedGrantClaimsAndExcessiveLifetime() {
        assertRejected(
            verifier,
            signedToken(claims = validClaims().put("literp_permissions", "master-data.read"))
        )
        assertRejected(
            verifier,
            signedToken(
                claims = validClaims().put(
                    "literp_locations",
                    JsonArray().add("not-a-canonical-uuid")
                )
            )
        )

        val now = Instant.now().epochSecond
        assertRejected(
            verifier,
            signedToken(
                claims = validClaims()
                    .put("iat", now)
                    .put("exp", now + 301)
            )
        )
    }

    @Test
    fun preservesWrongOrganizationForAuthorizationToRejectLater() {
        val principal = verify(
            verifier,
            signedToken(claims = validClaims().put("literp_org", "another-organization"))
        )

        assertEquals("another-organization", principal.organizationId)
    }

    @Test
    fun verifiesAKeyFromAConfiguredRotationSetAndRejectsUnknownKeyIds() {
        val rotatedKey = generateKeyPair()
        writeJwks(
            publicJwk(signingKey, "key-1"),
            publicJwk(rotatedKey, "key-2")
        )
        val rotatedVerifier = newVerifier()

        assertEquals("subject-1", verify(
            rotatedVerifier,
            signedToken(keyPair = rotatedKey, kid = "key-2")
        ).subject)

        val unknownKey = generateKeyPair()
        assertRejected(
            rotatedVerifier,
            signedToken(keyPair = unknownKey, kid = "unknown-key")
        )
    }

    @Test
    fun rejectsDuplicateAndPrivateKeysInConfiguredJwks() {
        writeJwks(
            publicJwk(signingKey, "duplicate"),
            publicJwk(signingKey, "duplicate")
        )
        assertFailsWith<IllegalStateException> { newVerifier() }

        writeJwks(publicJwk(signingKey, "public-only").put("d", "private-material"))
        assertFailsWith<IllegalStateException> { newVerifier() }
    }

    private fun newVerifier(): JwtCredentialVerifier = JwtCredentialVerifier(
        vertx,
        SecurityConfig(
            issuer = issuer,
            audience = audience,
            organizationId = organizationId,
            jwksPath = jwksPath
        )
    )

    private fun verify(verifier: JwtCredentialVerifier, token: String): AuthenticatedPrincipal =
        verifier.verify(token).toCompletionStage().toCompletableFuture().get()

    private fun assertRejected(verifier: JwtCredentialVerifier, token: String) {
        val failure = assertFailsWith<ExecutionException> {
            verify(verifier, token)
        }
        assertIs<InvalidCredential>(failure.cause)
    }

    private fun signedToken(
        keyPair: KeyPair = signingKey,
        kid: String = "key-1",
        claims: JsonObject = validClaims(),
        headerType: String = "Bearer",
        algorithm: String = "RS256",
        signatureAlgorithm: String = "SHA256withRSA"
    ): String {
        val header = JsonObject()
            .put("typ", headerType)
            .put("alg", algorithm)
            .put("kid", kid)
        val headerSegment = base64Url(header.encode().toByteArray(StandardCharsets.UTF_8))
        val payloadSegment = base64Url(claims.encode().toByteArray(StandardCharsets.UTF_8))
        val signingInput = "$headerSegment.$payloadSegment"
        val signature = Signature.getInstance(signatureAlgorithm).apply {
            initSign(keyPair.private)
            update(signingInput.toByteArray(StandardCharsets.US_ASCII))
        }.sign()

        return "$signingInput.${base64Url(signature)}"
    }

    private fun validClaims(): JsonObject {
        val now = Instant.now().epochSecond
        return JsonObject()
            .put("iss", issuer)
            .put("sub", "subject-1")
            .put("aud", audience)
            .put("iat", now)
            .put("exp", now + 300)
            .put("literp_org", organizationId)
            .put("literp_permissions", JsonArray().add("master-data.read").add("order.read"))
            .put("literp_locations", JsonArray().add(locationId))
            .put("literp_kind", "human")
            .put("literp_operator", true)
    }

    private fun writeJwks(vararg keys: JsonObject) {
        val keyArray = JsonArray()
        keys.forEach { keyArray.add(it) }
        Files.writeString(
            jwksPath,
            JsonObject().put("keys", keyArray).encode(),
            StandardCharsets.UTF_8
        )
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
}
