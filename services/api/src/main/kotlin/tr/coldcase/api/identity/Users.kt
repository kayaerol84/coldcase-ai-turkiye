package tr.coldcase.api.identity

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tr.coldcase.api.audit.AuditLog
import java.util.UUID

data class AppUser(val id: UUID, val subject: String, val email: String?, val displayName: String?, val platformAdmin: Boolean)

@Service
class UserService(private val jdbc: JdbcClient, private val audit: AuditLog) {

    /** Resolves the authenticated principal to an application user, provisioning it on first sight. */
    @Transactional
    fun current(): AppUser {
        val jwt = SecurityContextHolder.getContext().authentication?.principal as? Jwt
            ?: error("No authenticated JWT principal")
        return upsert(jwt)
    }

    private fun upsert(jwt: Jwt): AppUser {
        val email = jwt.getClaimAsString("email")
        val name = jwt.getClaimAsString("name") ?: jwt.getClaimAsString("preferred_username")
        return jdbc.sql(
            """
            INSERT INTO users (id, oidc_subject, email, display_name)
            VALUES (:id, :sub, :email, :name)
            ON CONFLICT (oidc_subject) DO UPDATE
               SET email = EXCLUDED.email, display_name = EXCLUDED.display_name, last_seen_at = now()
            RETURNING id, oidc_subject, email, display_name, platform_admin, (xmax = 0) AS inserted
            """.trimIndent()
        )
            .param("id", UUID.randomUUID())
            .param("sub", jwt.subject)
            .param("email", email)
            .param("name", name)
            .query { rs, _ ->
                AppUser(
                    rs.getObject("id", UUID::class.java), rs.getString("oidc_subject"),
                    rs.getString("email"), rs.getString("display_name"), rs.getBoolean("platform_admin"),
                ) to rs.getBoolean("inserted")
            }
            .single()
            .let { (user, inserted) ->
                if (inserted) audit.record(user.id, "USER_PROVISIONED", targetType = "user", targetId = user.id.toString())
                user
            }
    }

    fun findIdBySubject(subject: String): UUID? =
        jdbc.sql("SELECT id FROM users WHERE oidc_subject = :s").param("s", subject)
            .query(UUID::class.java).optional().orElse(null)
}
