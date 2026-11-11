package tr.coldcase.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Files
import java.util.UUID

/** Shared setup: real PostgreSQL (DB_URL), mocked JWT decoding, filesystem object store, worker disabled. */
@SpringBootTest
@AutoConfigureMockMvc
abstract class ApiTestBase {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var jdbc: JdbcClient
    @MockitoBean lateinit var jwtDecoder: JwtDecoder

    data class TestUser(val sub: String = "sub-" + UUID.randomUUID()) {
        val email = "$sub@example.org"
    }

    fun MockHttpServletRequestBuilder.asUser(u: TestUser): MockHttpServletRequestBuilder =
        with(jwt().jwt { it.subject(u.sub).claim("email", u.email).claim("name", "User ${u.sub.takeLast(4)}") })

    fun json(body: Any): String = mapper.writeValueAsString(body)

    fun readJson(s: String): JsonNode = mapper.readTree(s)

    fun createCase(owner: TestUser, title: String = "Uğur Mumcu — TBMM Cilt 2"): String {
        val res = mvc.perform(
            post("/api/v1/cases").asUser(owner).contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("title" to title, "description" to "Demo corpus")))
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.myRole").value("CASE_ADMIN"))
            .andExpect(jsonPath("$.primaryLanguage").value("tr"))
            .andReturn()
        return readJson(res.response.contentAsString)["id"].asText()
    }

    fun addMember(caseId: String, admin: TestUser, member: TestUser, role: String) {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/me").asUser(member))
        mvc.perform(
            put("/api/v1/cases/$caseId/members").asUser(admin).contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("email" to member.email, "role" to role)))
        ).andExpect(status().isOk)
    }

    companion object {
        val storageRoot = Files.createTempDirectory("coldcase-test-objects")

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("app.storage.type") { "filesystem" }
            registry.add("app.storage.filesystem-root") { storageRoot.toString() }
            registry.add("app.worker.enabled") { "false" }
        }
    }
}
