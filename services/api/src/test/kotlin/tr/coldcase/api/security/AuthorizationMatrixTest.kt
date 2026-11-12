package tr.coldcase.api.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import tr.coldcase.api.ApiTestBase
import tr.coldcase.api.TestPdfs
import tr.coldcase.api.TestPdfs.TextPage
import tr.coldcase.api.cases.CaseRole
import tr.coldcase.api.processing.DocumentProcessor
import java.util.UUID

/**
 * IDOR / access-control matrix (TR §16 security tests). Every /api endpoint is discovered from Spring's
 * handler mappings and must have a rule here, so a new endpoint cannot ship without its access rules
 * being tested. For each endpoint:
 *  - unauthenticated → 401
 *  - authenticated non-member → 404 (case existence is not disclosed)
 *  - member below the required role → 403
 *  - member at or above the required role → not denied
 */
@Tag("security")
class AuthorizationMatrixTest : ApiTestBase() {
    @Autowired @Qualifier("requestMappingHandlerMapping") lateinit var mappings: RequestMappingHandlerMapping
    @Autowired lateinit var processor: DocumentProcessor

    /** null minRole = any authenticated user, not case-scoped. */
    private data class Rule(val minRole: CaseRole?, val body: (Ctx) -> Any? = { null }, val multipart: Boolean = false)

    private class Ctx(val caseId: String, val documentId: String, val victim: ApiTestBase.TestUser, val victimId: String)

    private val rules: Map<String, Rule> = mapOf(
        // API schema: authenticated users only (PUBLIC_API_DOCS=true opens it for local development).
        "GET /api/openapi" to Rule(null),
        "GET /api/openapi.yaml" to Rule(null),
        "GET /api/openapi/swagger-config" to Rule(null),
        "GET /api/v1/me" to Rule(null),
        "GET /api/v1/cases" to Rule(null),
        "POST /api/v1/cases" to Rule(null, { mapOf("title" to "Yeni dosya") }),
        "GET /api/v1/cases/{caseId}" to Rule(CaseRole.VIEWER),
        "PATCH /api/v1/cases/{caseId}" to Rule(CaseRole.CASE_ADMIN, { mapOf("title" to "Değişti") }),
        "GET /api/v1/cases/{caseId}/members" to Rule(CaseRole.VIEWER),
        "PUT /api/v1/cases/{caseId}/members" to Rule(CaseRole.CASE_ADMIN, { mapOf("email" to it.victim.email, "role" to "VIEWER") }),
        "DELETE /api/v1/cases/{caseId}/members/{userId}" to Rule(CaseRole.CASE_ADMIN),
        "POST /api/v1/cases/{caseId}/documents" to Rule(CaseRole.CASE_ADMIN, multipart = true),
        "GET /api/v1/cases/{caseId}/documents" to Rule(CaseRole.VIEWER),
        "POST /api/v1/cases/{caseId}/search" to Rule(CaseRole.VIEWER, { mapOf("query" to "kurgusal") }),
        "GET /api/v1/documents/{documentId}" to Rule(CaseRole.VIEWER),
        "GET /api/v1/documents/{documentId}/pages" to Rule(CaseRole.VIEWER),
        "GET /api/v1/documents/{documentId}/pages/{pageNumber}" to Rule(CaseRole.VIEWER),
        "GET /api/v1/documents/{documentId}/pages/{pageNumber}/image" to Rule(CaseRole.VIEWER),
        "POST /api/v1/documents/{documentId}/pages/{pageNumber}/reports" to Rule(CaseRole.RESEARCHER, { mapOf("kind" to "OTHER", "note" to "test") }),
        "GET /api/v1/documents/{documentId}/reports" to Rule(CaseRole.VIEWER),
        "GET /api/v1/documents/{documentId}/file" to Rule(CaseRole.VIEWER),
        "POST /api/v1/documents/{documentId}/reprocess" to Rule(CaseRole.CASE_ADMIN),
    )

    private fun endpoints(): List<Pair<HttpMethod, String>> =
        mappings.handlerMethods.keys.flatMap { info ->
            val patterns = info.pathPatternsCondition?.patternValues ?: info.patternValues
            val methods = info.methodsCondition.methods.ifEmpty { setOf(org.springframework.web.bind.annotation.RequestMethod.GET) }
            patterns.filter { it.startsWith("/api/") }.flatMap { p -> methods.map { HttpMethod.valueOf(it.name) to p } }
        }.distinct()

    @Test
    fun `every API endpoint has an access rule`() {
        val missing = endpoints().map { (m, p) -> "${m.name()} $p" }.filter { it !in rules }
        assertThat(missing).describedAs("Add these endpoints to AuthorizationMatrixTest.rules").isEmpty()
        val stale = rules.keys - endpoints().map { (m, p) -> "${m.name()} $p" }.toSet()
        assertThat(stale).describedAs("Rules for endpoints that no longer exist").isEmpty()
    }

    private fun build(method: HttpMethod, pattern: String, ctx: Ctx, rule: Rule): MockHttpServletRequestBuilder {
        val uri = pattern
            .replace("{caseId}", ctx.caseId).replace("{documentId}", ctx.documentId)
            .replace("{pageNumber}", "1").replace("{userId}", ctx.victimId)
        if (rule.multipart) {
            return multipart(uri).file(MockMultipartFile("file", "a.pdf", "application/pdf", TestPdfs.build(listOf(TextPage(listOf("Kurgusal tutanak metni burada."))))))
        }
        val b = request(method, uri)
        rule.body(ctx)?.let { b.contentType(MediaType.APPLICATION_JSON).content(json(it)) }
        return b
    }

    private fun status(b: MockHttpServletRequestBuilder) = mvc.perform(b).andReturn().response.status

    @Test
    fun `access matrix holds for every endpoint and role`() {
        val owner = TestUser()
        val outsider = TestUser()
        val members = mapOf(CaseRole.VIEWER to TestUser(), CaseRole.RESEARCHER to TestUser(), CaseRole.REVIEWER to TestUser())
        val victim = TestUser()
        val caseId = createCase(owner)
        members.forEach { (role, u) -> addMember(caseId, owner, u, role.name) }
        addMember(caseId, owner, victim, "VIEWER")
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/me").asUser(outsider))

        val upload = mvc.perform(
            multipart("/api/v1/cases/$caseId/documents")
                .file(MockMultipartFile("file", "t.pdf", "application/pdf", TestPdfs.build(listOf(TextPage(listOf("Kurgusal bir tanık ifadesi.")))))).asUser(owner)
        ).andReturn()
        val doc = readJson(upload.response.contentAsString)
        processor.drain(UUID.fromString(doc["file"]["versionId"].asText()))
        val victimId = jdbc.sql("SELECT id FROM users WHERE oidc_subject = :s").param("s", victim.sub).query(UUID::class.java).single().toString()
        val ctx = Ctx(caseId, doc["id"].asText(), victim, victimId)

        val failures = mutableListOf<String>()
        fun expect(label: String, actual: Int, ok: (Int) -> Boolean, want: String) {
            if (!ok(actual)) failures += "$label: got $actual, want $want"
        }
        val allowedOk = { s: Int -> s !in setOf(401, 403, 404) && s < 500 }

        val ordered = endpoints().sortedBy { (m, _) -> if (m == HttpMethod.DELETE) 1 else 0 } // destructive last
        // Denials first, so allowed mutations can't change what the denials see.
        for ((method, pattern) in ordered) {
            val rule = rules["${method.name()} $pattern"] ?: continue
            val key = "${method.name()} $pattern"
            expect("$key unauthenticated", status(build(method, pattern, ctx, rule)), { it == 401 }, "401")
            if (rule.minRole == null) continue
            expect("$key outsider", status(build(method, pattern, ctx, rule).asUser(outsider)), { it == 404 }, "404")
            for ((role, user) in members) {
                if (role.ordinal < rule.minRole.ordinal) {
                    expect("$key as $role", status(build(method, pattern, ctx, rule).asUser(user)), { it == 403 }, "403")
                }
            }
        }
        // Then every role that should be allowed, including the owner (Case Admin).
        for ((method, pattern) in ordered) {
            val rule = rules["${method.name()} $pattern"] ?: continue
            val key = "${method.name()} $pattern"
            if (rule.minRole == null) {
                expect("$key outsider (any authenticated user)", status(build(method, pattern, ctx, rule).asUser(outsider)), allowedOk, "allowed")
                continue
            }
            if (method != HttpMethod.DELETE) {
                for ((role, user) in members) if (role.ordinal >= rule.minRole.ordinal) {
                    expect("$key as $role", status(build(method, pattern, ctx, rule).asUser(user)), allowedOk, "allowed")
                }
            }
            expect("$key as CASE_ADMIN", status(build(method, pattern, ctx, rule).asUser(owner)), allowedOk, "allowed")
        }
        assertThat(failures).describedAs(failures.joinToString("\n")).isEmpty()
    }

    @Test
    fun `server-owned fields cannot be set through the API`() {
        val owner = TestUser()
        val caseId = createCase(owner)
        val forgedId = UUID.randomUUID().toString()
        val res = mvc.perform(
            request(HttpMethod.PATCH, "/api/v1/cases/$caseId").asUser(owner).contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("title" to "Yeni", "id" to forgedId, "createdBy" to forgedId, "myRole" to "VIEWER", "createdAt" to "2000-01-01T00:00:00Z")))
        ).andReturn()
        val body = readJson(res.response.contentAsString)
        assertThat(body["id"].asText()).isEqualTo(caseId)
        assertThat(body["myRole"].asText()).isEqualTo("CASE_ADMIN")
        val createdBy = jdbc.sql("SELECT created_by::text FROM cases WHERE id = :id").param("id", UUID.fromString(caseId)).query(String::class.java).single()
        assertThat(createdBy).isNotEqualTo(forgedId)
    }
}
