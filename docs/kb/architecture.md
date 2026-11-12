# Architecture

Monorepo. One deployable backend (modular monolith), one web app, local infra via compose.

| Path | What |
|---|---|
| `services/api` | Kotlin 2.2 + Spring Boot 3.5, JDBC (`JdbcClient`, no ORM), Flyway, PostgreSQL 16 + pgvector + pg_trgm |
| `apps/web` | Next.js 15 (app router, client components), TypeScript, oidc-client-ts (PKCE) |
| `infra/docker/docker-compose.yml` | Dev stack: Postgres+pgvector, MinIO, Keycloak, TEI embedding server — all bound to localhost |
| `evals/` | Retrieval/citation benchmark format (questions pending the real PDF) |
| `.github/workflows/` | `ci.yml` (build/test), `security.yml` (secrets, dependencies, security tests, headers) |

## Backend modules (`services/api/src/main/kotlin/tr/coldcase/api/`)

| Package | Responsibility |
|---|---|
| `identity` | Users provisioned from JWT on first request; `/api/v1/me` |
| `cases` | Case aggregate, memberships, roles → permissions (`Access.kt`), the single authz choke point `CaseAccessService` |
| `documents` | Upload, document/version/page records, original download, page rendering, page reports, reprocess |
| `processing` | Job queue + worker: PDF inspection, per-page extraction, OCR (Tesseract), status derivation |
| `indexing` | Chunker (page-bounded, speaker-aware), segment store, embedding at index time |
| `search` | Hybrid search (FTS + vector + RRF), Turkish text folding, highlights |
| `embeddings` | Provider port + TEI adapter (self-hosted only) |
| `storage` | Object store port (filesystem / S3) + verified original cache |
| `audit` | Append-only audit log writer |
| `common`, `config` | Errors (`ApiException` + stable codes), hashing, properties, security config |

Dependencies between these are pinned in `domain/context-map.md` and enforced by
`services/api/src/test/kotlin/tr/coldcase/api/ContextBoundariesTest.kt`.

## Request path
JWT (resource server) → `UserService.current()` (provision/upsert) → service → `CaseAccessService.require(caseId, userId, permission)`
→ repository (SQL always filtered by `case_id`) → audit where relevant. Non-members get **404**, members lacking a
permission get **403**.

## Document pipeline (asynchronous)
1. `POST /api/v1/cases/{caseId}/documents` (multipart): spool to disk, SHA-256, content validation (`PdfInspector`),
   duplicate check per case, store original at `cases/{caseId}/originals/{sha256}.pdf` (never overwritten),
   insert document + version + `EXTRACT_DOCUMENT` job, audit. Returns 202.
2. Worker (`ProcessingWorker`, `DocumentProcessor`) claims jobs from `processing_jobs` (`FOR UPDATE SKIP LOCKED`, leases,
   exponential backoff, max attempts). Every run re-verifies the original's SHA-256.
3. `EXTRACT_DOCUMENT`: per-page PDFBox text, quality score, printed page label; empty/garbled pages → `NEEDS_OCR`,
   batched into `OCR_PAGES` jobs.
4. `OCR_PAGES`: render page (300 DPI) → Tesseract `tur` → OCR text + confidence stored **separately** from embedded text.
5. When no extraction/OCR job remains, `INDEX_DOCUMENT` is enqueued (at most one queued per version): chunk, reuse
   vectors for unchanged text hashes, embed the rest, replace segments atomically, `ANALYZE`.
6. Document status is derived (`refreshDocumentStatus`): `PROCESSING`, `READY`, `READY_WITH_WARNINGS` (+ detail code),
   `FAILED`.

## Search
`POST /api/v1/cases/{caseId}/search` with mode `HYBRID | KEYWORD | SEMANTIC | EXACT` and filters.
- Lexical: `text_segments.search_tsv` = Turkish stems ∪ folded tokens (`coldcase_fold`). Hybrid uses an OR query of
  stems + folded prefixes; keyword mode uses web-search syntax (AND, quotes, `-exclude`).
- Exact: `coldcase_fold(text) LIKE` with a trigram index.
- Semantic: exact cosine scan within the case (no ANN index), query vector parsed once in a CTE.
- Fusion: reciprocal rank fusion (k=60) + bonus for quoted phrases; overlapping chunks deduplicated.
- Falls back to keyword when embeddings are unavailable (`modeUsed`, `semanticAvailable` in the response).

## Data model (Flyway: `services/api/src/main/resources/db/migration/`)
`V1__foundation.sql` users, cases, case_memberships, audit_events (append-only trigger) ·
`V2__documents_and_processing.sql` documents, document_versions, pages, processing_jobs ·
`V3__retrieval.sql` text_segments (+tsvector, trigram), segment_embeddings, page_reports, `coldcase_fold()`.

## Endpoints (all under `/api/v1`, all authenticated)
`me` · `cases` (CRUD, `members`) · `cases/{id}/documents` (upload, list) · `documents/{id}` (get, `pages`,
`pages/{n}`, `pages/{n}/image`, `pages/{n}/reports`, `reports`, `file`, `reprocess`) · `cases/{id}/search`.
Actuator (`health`, `prometheus`) only on the internal management port 8081. OpenAPI (`/api/openapi`) requires
login unless `PUBLIC_API_DOCS=true`.

## Web (`apps/web/src/app/`)
`/` cases · `/cases/[caseId]` case + documents + members · `/cases/[caseId]/search` ·
`/documents/[documentId]` pages table · `/documents/[documentId]/pages/[pageNumber]` viewer ·
`/auth/callback`. API client and Turkish labels in `apps/web/src/lib/api.ts`.
