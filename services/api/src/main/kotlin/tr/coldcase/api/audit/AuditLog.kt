package tr.coldcase.api.audit

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Append-only audit trail (TR §12.3). Details must contain identifiers and metadata only,
 * never document text or prompts.
 */
@Service
class AuditLog(private val jdbc: JdbcClient, private val mapper: ObjectMapper) {
    fun record(
        actorId: UUID?,
        action: String,
        caseId: UUID? = null,
        targetType: String? = null,
        targetId: String? = null,
        details: Map<String, Any?> = emptyMap(),
    ) {
        jdbc.sql(
            """
            INSERT INTO audit_events (actor_id, case_id, action, target_type, target_id, details)
            VALUES (:actor, :case, :action, :tt, :tid, CAST(:details AS jsonb))
            """.trimIndent()
        )
            .param("actor", actorId)
            .param("case", caseId)
            .param("action", action)
            .param("tt", targetType)
            .param("tid", targetId)
            .param("details", mapper.writeValueAsString(details))
            .update()
    }
}
