package tr.coldcase.api.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
class SecurityConfig {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() } // stateless bearer-token API, no cookies
            .cors { }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers("/actuator/health", "/api/openapi/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                it.anyRequest().authenticated()
            }
            .oauth2ResourceServer { it.jwt { } }
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
