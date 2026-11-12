package tr.coldcase.api.search

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.*
import tr.coldcase.api.identity.UserService
import java.util.UUID

data class SearchFiltersRequest(
    @field:Size(max = 100) val documentIds: List<UUID>? = null,
    @field:Positive val pdfPageFrom: Int? = null,
    @field:Positive val pdfPageTo: Int? = null,
    @field:Size(max = 100) val speaker: String? = null,
    @field:Pattern(regexp = "EMBEDDED_TEXT|OCR") val sourceKind: String? = null,
)

data class SearchRequest(
    @field:NotBlank @field:Size(max = 500) val query: String,
    val mode: SearchMode = SearchMode.HYBRID,
    @field:Valid val filters: SearchFiltersRequest? = null,
    val limit: Int? = null,
)

@RestController
class SearchController(private val search: SearchService, private val users: UserService) {
    @PostMapping("/api/v1/cases/{caseId}/search")
    fun search(@PathVariable caseId: UUID, @Valid @RequestBody req: SearchRequest): SearchResult {
        val f = req.filters
        return search.search(
            users.current(), caseId, req.query, req.mode,
            SearchFilters(f?.documentIds, f?.pdfPageFrom, f?.pdfPageTo, f?.speaker, f?.sourceKind),
            req.limit,
        )
    }
}
