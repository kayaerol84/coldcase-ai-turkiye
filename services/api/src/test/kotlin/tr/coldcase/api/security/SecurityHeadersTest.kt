package tr.coldcase.api.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import tr.coldcase.api.ApiTestBase

/** Response hardening and error hygiene (TR §12). */
@Tag("security")
class SecurityHeadersTest : ApiTestBase() {

    @Test
    fun `API responses carry hardening headers`() {
        mvc.perform(get("/api/v1/me").asUser(TestUser()))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("X-Frame-Options", "DENY"))
            .andExpect(header().string("Referrer-Policy", "no-referrer"))
            .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("frame-ancestors 'none'")))
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
    }

    @Test
    fun `errors reveal no internals`() {
        val u = TestUser()
        val probes = listOf(
            mvc.perform(get("/api/v1/cases/not-a-uuid").asUser(u)),
            mvc.perform(post("/api/v1/cases").asUser(u).contentType(MediaType.APPLICATION_JSON).content("{broken json")),
            mvc.perform(post("/api/v1/cases").asUser(u).contentType(MediaType.APPLICATION_JSON).content("""{"title": ["x"]}""")),
            mvc.perform(get("/api/v1/does-not-exist").asUser(u)),
        )
        for (p in probes) {
            val r = p.andReturn().response
            assertThat(r.status).isBetween(400, 499)
            assertThat(r.contentAsString).doesNotContain("Exception", "tr.coldcase", "at org.", "trace", "SQL")
        }
    }

    @Test
    fun `operational endpoints are not served on the public port`() {
        for (path in listOf("/actuator/env", "/actuator/heapdump", "/actuator/prometheus", "/actuator/health")) {
            val s = mvc.perform(get(path).asUser(TestUser())).andReturn().response.status
            assertThat(s).describedAs(path).isNotEqualTo(200)
        }
    }
}
