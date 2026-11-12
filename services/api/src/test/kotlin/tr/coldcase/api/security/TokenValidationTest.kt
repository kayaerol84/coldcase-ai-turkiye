package tr.coldcase.api.security

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.PlainJWT
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.net.InetSocketAddress
import java.nio.file.Files
import java.time.Instant
import java.util.Date
import java.util.UUID

/**
 * Exercises the production JWT wiring (issuer discovery, JWKS, issuer and audience validation) with a real
 * decoder, against a local issuer — unlike the other API tests, which mock JWT decoding.
 */
@Tag("security")
@SpringBootTest
@AutoConfigureMockMvc
class TokenValidationTest {
    @Autowired lateinit var mvc: MockMvc

    companion object {
        private val key: RSAKey = RSAKeyGenerator(2048).keyID("k1").generate()
        private val otherKey: RSAKey = RSAKeyGenerator(2048).keyID("k1").generate() // same kid, different key
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val issuer: String

        init {
            issuer = "http://127.0.0.1:${server.address.port}/realms/test"
            server.createContext("/") { ex ->
                val body = when (ex.requestURI.path) {
                    "/realms/test/.well-known/openid-configuration" ->
                        """{"issuer":"$issuer","jwks_uri":"$issuer/certs","subject_types_supported":["public"],
                           "id_token_signing_alg_values_supported":["RS256"],"response_types_supported":["code"],
                           "authorization_endpoint":"$issuer/auth","token_endpoint":"$issuer/token"}"""
                    "/realms/test/certs" -> JWKSet(key.toPublicJWK()).toString()
                    else -> null
                }
                val bytes = (body ?: "{}").toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(if (body == null) 404 else 200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            server.start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri") { issuer }
            registry.add("app.worker.enabled") { "false" }
            registry.add("app.storage.filesystem-root") { Files.createTempDirectory("tv-objects").toString() }
            registry.add("app.storage.cache-dir") { Files.createTempDirectory("tv-cache").toString() }
        }

        @JvmStatic
        @AfterAll
        fun stop() = server.stop(0)
    }

    private fun claims(
        iss: String = issuer,
        aud: List<String>? = listOf("coldcase-api"),
        exp: Instant = Instant.now().plusSeconds(300),
    ): JWTClaimsSet = JWTClaimsSet.Builder()
        .issuer(iss).subject("sub-" + UUID.randomUUID()).audience(aud)
        .issueTime(Date.from(Instant.now().minusSeconds(10))).expirationTime(Date.from(exp))
        .claim("email", "tv-${UUID.randomUUID()}@example.org")
        .build()

    private fun rs256(c: JWTClaimsSet, k: RSAKey = key) =
        SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(k.keyID).build(), c).apply { sign(RSASSASigner(k)) }.serialize()

    private fun call(token: String) = mvc.perform(get("/api/v1/me").header("Authorization", "Bearer $token"))

    @Test
    fun `a valid token for this API is accepted`() {
        call(rs256(claims())).andExpect(status().isOk)
    }

    @Test
    fun `tokens failing any check are rejected`() {
        val bad = mapOf(
            "wrong issuer" to rs256(claims(iss = "https://evil.example/realms/test")),
            "audience for another client" to rs256(claims(aud = listOf("account"))),
            "no audience" to rs256(claims(aud = null)),
            "expired" to rs256(claims(exp = Instant.now().minusSeconds(3600))),
            "signed with another key" to rs256(claims(), otherKey),
            "unsigned (alg none)" to PlainJWT(claims()).serialize(),
            "HS256 with the public key as secret" to SignedJWT(JWSHeader(JWSAlgorithm.HS256), claims())
                .apply { sign(MACSigner(key.toRSAPublicKey().encoded.copyOf(64))) }.serialize(),
            "garbage" to "not.a.jwt",
        )
        for ((label, token) in bad) {
            val s = call(token).andReturn().response.status
            check(s == 401) { "$label: expected 401, got $s" }
        }
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized)
    }
}
