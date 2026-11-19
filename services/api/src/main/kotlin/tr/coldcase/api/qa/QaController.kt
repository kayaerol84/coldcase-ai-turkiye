package tr.coldcase.api.qa

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import tr.coldcase.api.identity.UserService
import tr.coldcase.api.search.SearchFilters
import tr.coldcase.api.search.SearchFiltersRequest
import java.util.UUID

data class QuestionRequest(
    @field:NotBlank @field:Size(max = 1000) val question: String,
    @field:Valid val filters: SearchFiltersRequest? = null,
)

data class CitationReportRequest(val kind: CitationReportKind, @field:Size(max = 2000) val note: String? = null)

data class QaStatus(val available: Boolean)

@RestController
@RequestMapping("/api/v1")
class QaController(private val qa: QaService, private val users: UserService) {

    @PostMapping("/cases/{caseId}/questions")
    @ResponseStatus(HttpStatus.CREATED)
    fun ask(@PathVariable caseId: UUID, @Valid @RequestBody req: QuestionRequest): AnswerView {
        val f = req.filters
        return qa.ask(users.current(), caseId, req.question,
            SearchFilters(f?.documentIds, f?.pdfPageFrom, f?.pdfPageTo, f?.speaker, f?.sourceKind))
    }

    @GetMapping("/cases/{caseId}/answers")
    fun list(@PathVariable caseId: UUID) = qa.list(users.current(), caseId)

    @GetMapping("/answers/{answerId}")
    fun get(@PathVariable answerId: UUID) = qa.get(users.current(), answerId)

    @PostMapping("/answers/{answerId}/citations/{citationId}/reports")
    @ResponseStatus(HttpStatus.CREATED)
    fun report(@PathVariable answerId: UUID, @PathVariable citationId: UUID, @Valid @RequestBody req: CitationReportRequest) =
        qa.reportCitation(users.current(), answerId, citationId, req.kind, req.note)

    /** Whether Q&A is switched on (app.llm.provider). Not case-scoped; reveals no case data. */
    @GetMapping("/qa/status")
    fun status() = QaStatus(qa.available())
}
