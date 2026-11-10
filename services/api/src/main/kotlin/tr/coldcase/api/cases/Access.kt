package tr.coldcase.api.cases

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import tr.coldcase.api.common.ForbiddenException
import tr.coldcase.api.common.NotFoundException
import java.util.UUID

enum class Permission {
    CASE_VIEW,
    CASE_EDIT,
    MEMBERS_MANAGE,
    DOCUMENT_UPLOAD,
    RESEARCH_WRITE,   // findings, leads, notes, statement comparisons
    FINDING_REVIEW,
}

/** Case-level roles from BR §5.2. Platform Admin is a user flag and grants no case access by itself. */
enum class CaseRole(val permissions: Set<Permission>) {
    VIEWER(setOf(Permission.CASE_VIEW)),
    RESEARCHER(setOf(Permission.CASE_VIEW, Permission.RESEARCH_WRITE)),
    REVIEWER(setOf(Permission.CASE_VIEW, Permission.RESEARCH_WRITE, Permission.FINDING_REVIEW)),
    CASE_ADMIN(Permission.entries.toSet());

    fun allows(p: Permission) = p in permissions
}

/**
 * Single choke point for case-level authorisation (TR §3.3, §12.1).
 * Non-members get 404 rather than 403 so case existence is not disclosed.
 */
@Service
class CaseAccessService(private val jdbc: JdbcClient) {

    fun roleOf(caseId: UUID, userId: UUID): CaseRole? =
        jdbc.sql("SELECT role FROM case_memberships WHERE case_id = :c AND user_id = :u")
            .param("c", caseId).param("u", userId)
            .query(String::class.java).optional().map { CaseRole.valueOf(it) }.orElse(null)

    fun require(caseId: UUID, userId: UUID, permission: Permission): CaseRole {
        val role = roleOf(caseId, userId) ?: throw NotFoundException("Case not found")
        if (!role.allows(permission)) throw ForbiddenException("Missing permission $permission")
        return role
    }
}
