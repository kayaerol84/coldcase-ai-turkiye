package tr.coldcase.api.cases

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tr.coldcase.api.audit.AuditLog
import tr.coldcase.api.common.BadRequestException
import tr.coldcase.api.common.NotFoundException
import tr.coldcase.api.identity.AppUser
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

data class CreateCaseCommand(
    val title: String,
    val description: String?,
    val primaryLanguage: String?,
    val periodStart: LocalDate?,
    val periodEnd: LocalDate?,
)

/** Partial update: null means "leave unchanged". */
data class UpdateCaseCommand(
    val title: String?,
    val description: String?,
    val primaryLanguage: String?,
    val periodStart: LocalDate?,
    val periodEnd: LocalDate?,
)

@Service
class CaseService(
    private val repo: CaseRepository,
    private val access: CaseAccessService,
    private val audit: AuditLog,
) {
    @Transactional
    fun create(actor: AppUser, cmd: CreateCaseCommand): Pair<CaseRecord, CaseRole> {
        validatePeriod(cmd.periodStart, cmd.periodEnd)
        val now = OffsetDateTime.now()
        val record = CaseRecord(
            id = UUID.randomUUID(),
            title = cmd.title.trim(),
            description = cmd.description,
            primaryLanguage = cmd.primaryLanguage ?: "tr",
            periodStart = cmd.periodStart,
            periodEnd = cmd.periodEnd,
            createdBy = actor.id,
            createdAt = now,
            updatedAt = now,
        )
        repo.insert(record)
        repo.upsertMember(record.id, actor.id, CaseRole.CASE_ADMIN, actor.id)
        audit.record(actor.id, "CASE_CREATED", record.id, "case", record.id.toString())
        return repo.find(record.id)!! to CaseRole.CASE_ADMIN
    }

    fun list(actor: AppUser) = repo.listForUser(actor.id)

    fun get(actor: AppUser, caseId: UUID): Pair<CaseRecord, CaseRole> {
        val role = access.require(caseId, actor.id, Permission.CASE_VIEW)
        return (repo.find(caseId) ?: throw NotFoundException("Case not found")) to role
    }

    @Transactional
    fun update(actor: AppUser, caseId: UUID, cmd: UpdateCaseCommand): Pair<CaseRecord, CaseRole> {
        val role = access.require(caseId, actor.id, Permission.CASE_EDIT)
        val current = repo.find(caseId) ?: throw NotFoundException("Case not found")
        val updated = current.copy(
            title = cmd.title?.trim() ?: current.title,
            description = cmd.description ?: current.description,
            primaryLanguage = cmd.primaryLanguage ?: current.primaryLanguage,
            periodStart = cmd.periodStart ?: current.periodStart,
            periodEnd = cmd.periodEnd ?: current.periodEnd,
        )
        validatePeriod(updated.periodStart, updated.periodEnd)
        repo.update(updated)
        val changed = listOfNotNull(
            "title".takeIf { cmd.title != null }, "description".takeIf { cmd.description != null },
            "primaryLanguage".takeIf { cmd.primaryLanguage != null },
            "periodStart".takeIf { cmd.periodStart != null }, "periodEnd".takeIf { cmd.periodEnd != null },
        )
        audit.record(actor.id, "CASE_UPDATED", caseId, "case", caseId.toString(), mapOf("fields" to changed))
        return repo.find(caseId)!! to role
    }

    fun members(actor: AppUser, caseId: UUID): List<MemberRecord> {
        access.require(caseId, actor.id, Permission.CASE_VIEW)
        return repo.members(caseId)
    }

    @Transactional
    fun setMember(actor: AppUser, caseId: UUID, email: String, role: CaseRole): MemberRecord {
        access.require(caseId, actor.id, Permission.MEMBERS_MANAGE)
        val userId = repo.findUserIdByEmail(email)
            ?: throw BadRequestException("No user with that email has signed in yet")
        if (userId == actor.id && role != CaseRole.CASE_ADMIN) ensureAnotherAdmin(caseId)
        val previous = access.roleOf(caseId, userId)
        repo.upsertMember(caseId, userId, role, actor.id)
        audit.record(
            actor.id, "CASE_MEMBER_SET", caseId, "user", userId.toString(),
            mapOf("role" to role.name, "previousRole" to previous?.name),
        )
        return repo.members(caseId).first { it.userId == userId }
    }

    @Transactional
    fun removeMember(actor: AppUser, caseId: UUID, userId: UUID) {
        access.require(caseId, actor.id, Permission.MEMBERS_MANAGE)
        val role = access.roleOf(caseId, userId) ?: throw NotFoundException("Member not found")
        if (role == CaseRole.CASE_ADMIN) ensureAnotherAdmin(caseId)
        repo.removeMember(caseId, userId)
        audit.record(actor.id, "CASE_MEMBER_REMOVED", caseId, "user", userId.toString(), mapOf("role" to role.name))
    }

    private fun ensureAnotherAdmin(caseId: UUID) {
        if (repo.countAdmins(caseId) <= 1) throw BadRequestException("A case must keep at least one Case Admin")
    }

    private fun validatePeriod(start: LocalDate?, end: LocalDate?) {
        if (start != null && end != null && end.isBefore(start)) throw BadRequestException("periodEnd is before periodStart")
    }
}
