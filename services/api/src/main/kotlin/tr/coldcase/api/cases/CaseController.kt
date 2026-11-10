package tr.coldcase.api.cases

import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import tr.coldcase.api.identity.UserService
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

data class CreateCaseRequest(
    @field:NotBlank @field:Size(max = 300) val title: String,
    @field:Size(max = 10_000) val description: String? = null,
    @field:Pattern(regexp = "^[a-z]{2}(-[A-Z]{2})?$") val primaryLanguage: String? = null,
    val periodStart: LocalDate? = null,
    val periodEnd: LocalDate? = null,
)

data class UpdateCaseRequest(
    @field:Size(min = 1, max = 300) val title: String? = null,
    @field:Size(max = 10_000) val description: String? = null,
    @field:Pattern(regexp = "^[a-z]{2}(-[A-Z]{2})?$") val primaryLanguage: String? = null,
    val periodStart: LocalDate? = null,
    val periodEnd: LocalDate? = null,
)

data class CaseResponse(
    val id: UUID,
    val title: String,
    val description: String?,
    val primaryLanguage: String,
    val periodStart: LocalDate?,
    val periodEnd: LocalDate?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
    val myRole: CaseRole,
) {
    companion object {
        fun of(p: Pair<CaseRecord, CaseRole>) = p.first.let {
            CaseResponse(it.id, it.title, it.description, it.primaryLanguage, it.periodStart, it.periodEnd, it.createdAt, it.updatedAt, p.second)
        }
    }
}

data class SetMemberRequest(@field:NotBlank @field:Email val email: String, val role: CaseRole)

data class MemberResponse(val userId: UUID, val email: String?, val displayName: String?, val role: CaseRole, val grantedAt: OffsetDateTime)

private fun MemberRecord.toResponse() = MemberResponse(userId, email, displayName, role, grantedAt)

@RestController
@RequestMapping("/api/v1/cases")
class CaseController(private val cases: CaseService, private val users: UserService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody req: CreateCaseRequest) = CaseResponse.of(
        cases.create(users.current(), CreateCaseCommand(req.title, req.description, req.primaryLanguage, req.periodStart, req.periodEnd))
    )

    @GetMapping
    fun list() = cases.list(users.current()).map(CaseResponse::of)

    @GetMapping("/{caseId}")
    fun get(@PathVariable caseId: UUID) = CaseResponse.of(cases.get(users.current(), caseId))

    @PatchMapping("/{caseId}")
    fun update(@PathVariable caseId: UUID, @Valid @RequestBody req: UpdateCaseRequest) = CaseResponse.of(
        cases.update(users.current(), caseId, UpdateCaseCommand(req.title, req.description, req.primaryLanguage, req.periodStart, req.periodEnd))
    )

    @GetMapping("/{caseId}/members")
    fun members(@PathVariable caseId: UUID) = cases.members(users.current(), caseId).map { it.toResponse() }

    @PutMapping("/{caseId}/members")
    fun setMember(@PathVariable caseId: UUID, @Valid @RequestBody req: SetMemberRequest) =
        cases.setMember(users.current(), caseId, req.email, req.role).toResponse()

    @DeleteMapping("/{caseId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removeMember(@PathVariable caseId: UUID, @PathVariable userId: UUID) =
        cases.removeMember(users.current(), caseId, userId)
}
