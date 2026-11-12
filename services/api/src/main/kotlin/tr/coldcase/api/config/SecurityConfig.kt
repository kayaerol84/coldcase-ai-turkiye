package tr.coldcase.api.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
class SecurityConfig {
    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        @Value("\${app.security.public-api-docs:false}") publicApiDocs: Boolean,
    ): SecurityFilterChain {
        http
            .csrf { it.disable() } // stateless bearer-token API, no cookies
            .cors { }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                // Actuator endpoints are only served on the internal management port (management.server.port).
                it.requestMatchers("/actuator/**").permitAll()
                // The API schema is not public unless explicitly enabled (local development).
                if (publicApiDocs) it.requestMatchers("/api/openapi", "/api/openapi/**", "/api/openapi.yaml", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                it.anyRequest().authenticated()
            }
            .oauth2ResourceServer { it.jwt { } }
            .headers { h ->
                // JSON/PDF/PNG API: nothing should ever be framed, scripted or sniffed.
                h.contentSecurityPolicy { it.policyDirectives("default-src 'none'; frame-ancestors 'none'; base-uri 'none'") }
                h.referrerPolicy { it.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER) }
                h.frameOptions { it.deny() }
            }
        return http.build()
    }

    /** Explicit allow-list of web origins (comma-separated `app.cors.allowed-origins`). */
    @Bean
    fun corsConfigurationSource(@Value("\${app.cors.allowed-origins}") origins: List<String>): CorsConfigurationSource {
        val config = CorsConfiguration().apply {
            allowedOrigins = origins.map { it.trim() }.filter { it.isNotEmpty() }
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            allowedHeaders = listOf("Authorization", "Content-Type")
            maxAge = 3600
        }
        return UrlBasedCorsConfigurationSource().apply { registerCorsConfiguration("/api/**", config) }
    }
}
