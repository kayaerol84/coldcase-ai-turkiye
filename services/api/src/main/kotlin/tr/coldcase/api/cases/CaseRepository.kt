package tr.coldcase.api.cases

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

data class CaseRecord(
    val id: UUID,
    val title: String,
    val description: String?,
    val primaryLanguage: String,
    val periodStart: LocalDate?,
    val periodEnd: LocalDate?,
    val createdBy: UUID,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

data class MemberRecord(val userId: UUID, val email: String?, val displayName: String?, val role: CaseRole, val grantedAt: OffsetDateTime)

@Repository
class CaseRepository(private val jdbc: JdbcClient) {

    private val caseMapper = RowMapper { rs, _ ->
        CaseRecord(
            id = rs.getObject("id", UUID::class.java),
            title = rs.getString("title"),
            description = rs.getString("description"),
            primaryLanguage = rs.getString("primary_language"),
            periodStart = rs.getObject("period_start", LocalDate::class.java),
            periodEnd = rs.getObject("period_end", LocalDate::class.java),
            createdBy = rs.getObject("created_by", UUID::class.java),
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
            updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java),
        )
    }

    fun insert(c: CaseRecord) {
        jdbc.sql(
            """
            INSERT INTO cases (id, title, description, primary_language, period_start, period_end, created_by)
            VALUES (:id, :title, :description, :lang, :ps, :pe, :by)
            """.trimIndent()
        )
            .param("id", c.id).param("title", c.title).param("description", c.description)
            .param("lang", c.primaryLanguage).param("ps", c.periodStart).param("pe", c.periodEnd)
            .param("by", c.createdBy)
            .update()
    }

    fun find(id: UUID): CaseRecord? =
        jdbc.sql("SELECT * FROM cases WHERE id = :id").param("id", id).query(caseMapper).optional().orElse(null)

    fun listForUser(userId: UUID): List<Pair<CaseRecord, CaseRole>> =
        jdbc.sql(
            """
            SELECT c.*, m.role FROM cases c
            JOIN case_memberships m ON m.case_id = c.id
            WHERE m.user_id = :u
            ORDER BY c.created_at DESC
            """.trimIndent()
        )
            .param("u", userId)
            .query { rs, n -> caseMapper.mapRow(rs, n)!! to CaseRole.valueOf(rs.getString("role")) }
            .list()

    fun update(c: CaseRecord) {
        jdbc.sql(
            """
            UPDATE cases SET title = :title, description = :description, primary_language = :lang,
                   period_start = :ps, period_end = :pe, updated_at = now()
            WHERE id = :id
            """.trimIndent()
        )
            .param("id", c.id).param("title", c.title).param("description", c.description)
            .param("lang", c.primaryLanguage).param("ps", c.periodStart).param("pe", c.periodEnd)
            .update()
    }

    fun upsertMember(caseId: UUID, userId: UUID, role: CaseRole, grantedBy: UUID) {
        jdbc.sql(
            """
            INSERT INTO case_memberships (case_id, user_id, role, granted_by) VALUES (:c, :u, :r, :g)
            ON CONFLICT (case_id, user_id) DO UPDATE SET role = EXCLUDED.role, granted_by = EXCLUDED.granted_by, granted_at = now()
            """.trimIndent()
        )
            .param("c", caseId).param("u", userId).param("r", role.name).param("g", grantedBy)
            .update()
    }

    fun removeMember(caseId: UUID, userId: UUID): Int =
        jdbc.sql("DELETE FROM case_memberships WHERE case_id = :c AND user_id = :u")
            .param("c", caseId).param("u", userId).update()

    fun countAdmins(caseId: UUID): Int =
        jdbc.sql("SELECT count(*) FROM case_memberships WHERE case_id = :c AND role = 'CASE_ADMIN'")
            .param("c", caseId).query(Int::class.java).single()

    fun members(caseId: UUID): List<MemberRecord> =
        jdbc.sql(
            """
            SELECT u.id, u.email, u.display_name, m.role, m.granted_at
            FROM case_memberships m JOIN users u ON u.id = m.user_id
            WHERE m.case_id = :c ORDER BY m.granted_at
            """.trimIndent()
        )
            .param("c", caseId)
            .query { rs, _ ->
                MemberRecord(
                    rs.getObject("id", UUID::class.java), rs.getString("email"), rs.getString("display_name"),
                    CaseRole.valueOf(rs.getString("role")), rs.getObject("granted_at", OffsetDateTime::class.java),
                )
            }
            .list()

    fun findUserIdByEmail(email: String): UUID? =
        jdbc.sql("SELECT id FROM users WHERE lower(email) = lower(:e)").param("e", email)
            .query(UUID::class.java).optional().orElse(null)
}
