# Invariants

Rules from the requirements, where each is enforced, and the test that proves it. When you change one of
these code paths, run its test; when you add a rule, add a row.

| # | Rule (source) | Enforced in | Tested by |
|---|---|---|---|
| I1 | Non-members cannot see a case; it looks non-existent (BR §5.2, TR §3.3) | `CaseAccessService.require` → 404 | `CaseApiTest`, `DocumentApiTest`, `SearchApiTest`, `security/AuthorizationMatrixTest` |
| I2 | A case keeps at least one Case Admin | `CaseService.ensureAnotherAdmin` | `CaseApiTest` |
| I3 | Audit log is append-only (TR §12.3) | DB trigger in `V1__foundation.sql` | `CaseApiTest` |
| I4 | Originals are immutable and content-addressed (TR §3.1) | `ObjectKeys.original`, `ObjectStore.putIfAbsent`, read-only files | `DocumentApiTest` |
| I5 | Originals are verified against their SHA-256 before every use | `DocumentProcessor.withVerifiedOriginal`, `OriginalCache.get` | `DocumentApiTest` (tampered original) |
| I6 | Uploads validated by content, not filename (TR §5.1) | `PdfInspector` | `DocumentApiTest`, `security/UploadSecurityTest` |
| I7 | Duplicate file in a case is rejected and points to the existing document | unique index + `DocumentService.upload` | `DocumentApiTest` |
| I8 | Every page gets a row; empty pages are flagged, never dropped (TR §5.2) | `DocumentProcessor.extract` | `DocumentApiTest` |
| I9 | OCR text is stored separately from embedded text, with engine + version (TR §5.2) | `pages` columns, `DocumentRepository.recordOcr` | `DocumentApiTest` |
| I10 | PDF page number and printed label are separate; a wrong label is worse than none | `PrintedLabelDetector` | `ExtractionHeuristicsTest` |
| I11 | Every segment has exactly one page and exact offsets into that page's text (TR §3.2) | `Chunker` | `RetrievalUnitTest`, `SearchApiTest` |
| I12 | Turns by different speakers are never in one segment (TR §5.3) | `Chunker.splitTurns` | `RetrievalUnitTest` |
| I13 | Search is restricted to the case **before** ranking, in SQL (TR §6.2) | `SearchService.filterSql` | `SearchApiTest` (all modes) |
| I14 | Document text never goes to an external embedding API (ADR 0001) | `EmbeddingConfig` allows `none`/`tei` only | `RetrievalUnitTest` (TEI checks) |
| I15 | The served embedding model must match the configured model/revision | `TeiEmbeddingProvider.available` | `RetrievalUnitTest` |
| I16 | Retrieval scores are diagnostics, not proof (TR §6.2) | UI copy + `Diagnostics` naming | — (review) |
| I17 | Document text, queries and prompts are never logged (TR §14) | logging discipline | `security/LogLeakageTest` |
| I18 | AI output is a draft until a human reviews it (BR §2.1) | T3+ | — |
| I20 | Only tokens issued for audience `coldcase-api` by the configured issuer are accepted | `application.yml` (`audiences`), Keycloak audience mapper | `TokenValidationTest` |
| I21 | Every API endpoint has a tested access rule | `AuthorizationMatrixTest` (endpoint discovery) | itself |
| I19 | Test fixtures use fictional people; never attribute invented statements to real people | review | — (see conventions) |
