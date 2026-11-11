package tr.coldcase.api

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
class CaseApiTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var jdbc: JdbcClient
    @MockitoBean lateinit var jwtDecoder: JwtDecoder

    private data class TestUser(val sub: String = "sub-" + UUID.randomUUID()) {
        val email = "$sub@example.org"
    }

    private fun MockHttpServletRequestBuilder.asUser(u: TestUser) =
        with(jwt().jwt { it.subject(u.sub).claim("email", u.email).claim("name", "User ${u.sub.takeLast(4)}") })

    private fun json(body: Any) = mapper.writeValueAsString(body)

    private fun createCase(owner: TestUser, title: String = "Uğur Mumcu — TBMM Cilt 2"): String {
        val res = mvc.perform(
            post("/api/v1/cases").asUser(owner).contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("title" to title, "description" to "Demo corpus")))
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.myRole").value("CASE_ADMIN"))
            .andExpect(jsonPath("$.primaryLanguage").value("tr"))
            .andReturn()
        return mapper.readTree(res.response.contentAsString)["id"].asText()
    }

    /** Ensures the user row exists (users are provisioned on first authenticated request). */
    private fun signIn(u: TestUser) {
        mvc.perform(get("/api/v1/cases").asUser(u)).andExpect(status().isOk)
    }

    @Test
    fun `unauthenticated requests are rejected`() {
        mvc.perform(get("/api/v1/cases")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `creator becomes case admin and sees the case`() {
        val owner = TestUser()
        val id = createCase(owner)
        mvc.perform(get("/api/v1/cases/$id").asUser(owner))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("Uğur Mumcu — TBMM Cilt 2"))
        mvc.perform(get("/api/v1/cases").asUser(owner))
            .andExpect(jsonPath("$.length()").value(1))
    }

    @Test
    fun `non-member gets 404 for another user's case and does not see it in the list`() {
        val owner = TestUser()
        val outsider = TestUser()
        val id = createCase(owner)
        mvc.perform(get("/api/v1/cases/$id").asUser(outsider)).andExpect(status().isNotFound)
        mvc.perform(
            patch("/api/v1/cases/$id").asUser(outsider).contentType(MediaType.APPLICATION_JSON).content(json(mapOf("title" to "x")))
        ).andExpect(status().isNotFound)
        mvc.perform(get("/api/v1/cases/$id/members").asUser(outsider)).andExpect(status().isNotFound)
        mvc.perform(get("/api/v1/cases").asUser(outsider)).andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `viewer can read but not edit or manage members`() {
        val owner = TestUser()
        val viewer = TestUser()
        signIn(viewer)
        val id = createCase(owner)
        mvc.perform(
            put("/api/v1/cases/$id/members").asUser(owner).contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("email" to viewer.email, "role" to "VIEWER")))
        ).andExpect(status().isOk).andExpect(jsonPath("$.role").value("VIEWER"))

        mvc.perform(get("/api/v1/cases/$id").asUser(viewer))
            .andExpect(status().isOk).andExpect(jsonPath("$.myRole").value("VIEWER"))
        mvc.perform(
            patch("/api/v1/cases/$id").asUser(viewer).contentType(MediaType.APPLICATION_JSON).content(json(mapOf("title" to "x")))
        ).andExpect(status().isForbidden)
        mvc.perform(
            put("/api/v1/cases/$id/members").asUser(viewer).contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("email" to viewer.email, "role" to "CASE_ADMIN")))
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `last case admin cannot be removed or demoted`() {
        val owner = TestUser()
        val id = createCase(owner)
        val ownerId = jdbc.sql("SELECT id FROM users WHERE oidc_subject = :s").param("s", owner.sub).query(UUID::class.java).single()
        mvc.perform(delete("/api/v1/cases/$id/members/$ownerId").asUser(owner))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("LAST_CASE_ADMIN"))
        mvc.perform(
            put("/api/v1/cases/$id/members").asUser(owner).contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("email" to owner.email, "role" to "VIEWER")))
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `patch updates only provided fields and validates period`() {
        val owner = TestUser()
        val id = createCase(owner)
        mvc.perform(
            patch("/api/v1/cases/$id").asUser(owner).contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("periodStart" to "1993-01-24")))
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.periodStart").value("1993-01-24"))
            .andExpect(jsonPath("$.description").value("Demo corpus"))
        mvc.perform(
            patch("/api/v1/cases/$id").asUser(owner).contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("periodEnd" to "1990-01-01")))
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `blank title is rejected`() {
        mvc.perform(
            post("/api/v1/cases").asUser(TestUser()).contentType(MediaType.APPLICATION_JSON).content(json(mapOf("title" to " ")))
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `actions are audited and audit log is append-only`() {
        val owner = TestUser()
        val id = UUID.fromString(createCase(owner))
        val actions = jdbc.sql("SELECT action FROM audit_events WHERE case_id = :c").param("c", id).query(String::class.java).list()
        assertThat(actions).contains("CASE_CREATED")
        assertThrows<Exception> {
            jdbc.sql("DELETE FROM audit_events WHERE case_id = :c").param("c", id).update()
        }
        assertThrows<Exception> {
            jdbc.sql("UPDATE audit_events SET action = 'X' WHERE case_id = :c").param("c", id).update()
        }
    }

    @Test
    fun `me returns the provisioned user and provisioning is audited once`() {
        val u = TestUser()
        mvc.perform(get("/api/v1/me").asUser(u))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.email").value(u.email))
            .andExpect(jsonPath("$.platformAdmin").value(false))
        mvc.perform(get("/api/v1/me").asUser(u)).andExpect(status().isOk)
        val count = jdbc.sql(
            """
            SELECT count(*) FROM audit_events a JOIN users u ON a.actor_id = u.id
            WHERE u.oidc_subject = :s AND a.action = 'USER_PROVISIONED'
            """.trimIndent()
        ).param("s", u.sub).query(Int::class.java).single()
        assertThat(count).isEqualTo(1)
    }

    @Test
    fun `cors allows the configured web origin only`() {
        mvc.perform(
            options("/api/v1/cases").header("Origin", "http://localhost:3000").header("Access-Control-Request-Method", "POST")
        ).andExpect(status().isOk)
            .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
        mvc.perform(
            options("/api/v1/cases").header("Origin", "https://evil.example").header("Access-Control-Request-Method", "POST")
        ).andExpect(status().isForbidden)
    }
}
