# Conventions

## Code
- Kotlin, Spring Boot, `JdbcClient` with explicit SQL; no ORM. Repositories return plain data classes.
- One top-level package per bounded context or shared module; dependencies per `domain/context-map.md`.
- Authorisation happens in services via `CaseAccessService.require(...)` — never only in controllers or the UI.
- Errors: throw `ApiException(status, CODE, message)`; the web maps `CODE` to Turkish text in
  `apps/web/src/lib/api.ts`. Add new codes in both places.
- External systems only behind a port (`ObjectStore`, `EmbeddingProvider`, `OcrEngine`, future `LlmProvider`).
- Record versions of everything derived: extractor, OCR engine, chunker, embedding model (TR §3.7).

## Database
- Flyway migrations are append-only: never edit a released `V*__*.sql`; add a new version.
- Every case-owned table has `case_id`. Every source-derived row keeps `document_id`/`page_id`.
- PDF page numbers are 1-based. Store printed labels separately.

## Tests
- API tests need PostgreSQL (`DB_URL`, default `localhost:5432/coldcase`) with `vector` and `pg_trgm`.
- Extend `ApiTestBase` (real DB, mocked JWT decoding, filesystem store, worker off, fake embeddings).
  Drive the worker with `DocumentProcessor.drain(versionId)`.
- Security tests carry `@Tag("security")`, live in `services/api/src/test/kotlin/tr/coldcase/api/security/`, and run via
  `./gradlew securityTest` (and in `check`). A new endpoint needs a rule in `AuthorizationMatrixTest`.
- Dependencies: Gradle versions are locked in `services/api/gradle.lockfile` — after changing dependencies run
  `./gradlew dependencies --write-locks` and commit the lockfile. Pick releases at least two weeks old.
- Use **fictional people** in fixtures; generate PDFs with `TestPdfs`.

## Web
- UI text is Turkish; code identifiers English. Status is always shown as text, never colour alone.
- Never render document text as HTML; use `HighlightedText`.
- Tokens live in session storage only; the API is called with a bearer token, never cookies.

## Commits and docs
- Update the KB in the same change (see `README.md` protocol). New decisions: ADR in `docs/adr/` if architectural,
  otherwise a line in `decisions.md`.
