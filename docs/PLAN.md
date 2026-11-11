# ColdCase AI Türkiye — Implementation Plan

**Status:** v0.1, derived from `business_requirements.md` and `technical_requirements.md`.

This plan turns the requirement documents into an ordered build sequence. Each
milestone ends in something demonstrable and testable. AI features come *after*
provenance, permissions and audit are in place, because every AI output must be
traceable to a source page and permission-filtered.

---

## 1. Working defaults for open decisions

The requirement docs leave several decisions open. To start building we assume
the following; each is isolated behind an interface so it can change later.

| Open decision | Working default | Why / escape hatch |
|---|---|---|
| Single vs multi-user (BR §14.1) | Multi-user with case membership from day one | Roles (Viewer → Platform Admin) and IDOR tests require it; retrofitting is costly |
| Auth provider (TR §20.2) | Any OIDC provider; Keycloak in local dev | Backend is a plain JWT resource server |
| Queue technology (TR §20.4) | PostgreSQL job table (`FOR UPDATE SKIP LOCKED`) behind a `JobQueue` interface | No extra infra for MVP; swap for RabbitMQ/SQS later |
| Object storage (TR §20.3) | S3 API; MinIO locally | Same SDK works for AWS S3 / Scaleway / OVH in an EU region |
| Vector search | pgvector in the same PostgreSQL | As recommended in TR §2.2 |
| OCR (TR §20.5) | OCRmyPDF + Tesseract `tur` in the worker image | Benchmark Turkish quality in T1 |
| LLM / embeddings (TR §20.6) | Provider adapter interfaces; first adapters: Anthropic Claude for generation, a multilingual embedding model chosen by Turkish retrieval eval | No domain code imports a vendor SDK |
| Report format (TR §20.9) | Markdown first, PDF rendered from Markdown | BR §6.1 item 13 |
| Page numbering | `pdf_page_number` is **1-based**; `printed_page_label` stored separately | TR §4.2 requires one consistent convention |
| Deployment | Docker images; EU-region cloud TBD | Compose for local, Terraform later |

## 2. Repository layout

```text
coldcase-ai-turkiye/
├── apps/web/                      # Next.js + TypeScript research workspace
├── services/api/                  # Kotlin + Spring Boot modular monolith
├── workers/document-processor/    # PDF extraction, OCR, chunking, embeddings (Kotlin)
├── packages/api-contracts/        # OpenAPI spec (generated from the API, checked in)
├── infra/docker/                  # docker-compose: postgres+pgvector, minio, keycloak
├── evals/                         # benchmark questions + expected citations
├── docs/                          # requirements, plan, ADRs
└── .github/workflows/             # CI
```

The API is a **modular monolith**: one deployable, with packages per bounded
context (`identity`, `cases`, `documents`, `search`, `qa`, `investigation`,
`reports`, `audit`). Modules talk through service interfaces, never through
each other's tables.

## 3. Cross-cutting rules (enforced from T0)

1. **Case isolation:** every case-owned table has `case_id`; every request goes
   through `CaseAccessService.require(caseId, permission)` on the server.
   Unknown and forbidden cases both return 404 (no existence leak).
2. **Audit:** `audit_events` is append-only (DB trigger blocks UPDATE/DELETE).
3. **Immutable originals:** object keys are content-addressed
   (`cases/{caseId}/originals/{sha256}`); no code path overwrites them.
4. **Versioning:** every derived artefact records the producing pipeline
   version (extractor, OCR engine, chunker, embedding model, LLM + prompt).
5. **AI output is a draft:** all AI-produced records carry a review status that
   starts at `AI_DRAFT` / `AI_SUGGESTED`.
6. **No sensitive text in logs:** structured logs carry IDs, never document
   text or prompts.

## 4. Milestones

### T0 — Foundation *(done)*
- Monorepo, docker-compose (PostgreSQL 16 + pgvector, MinIO, Keycloak), CI.
- Spring Boot API: JWT resource server, user auto-provisioning from token.
- Schema V1: `users`, `cases`, `case_memberships`, `audit_events` (append-only).
- Case API: `POST/GET /api/v1/cases`, `GET/PATCH /api/v1/cases/{id}`,
  membership management.
- Role → permission matrix (Viewer, Researcher, Reviewer, Case Admin, Platform Admin).
- Tests: permission checks, cross-case access returns 404, audit written.
- Next.js shell with dark forensic theme tokens.
- Web login (OIDC Authorization Code + PKCE), case list, create/edit case,
  member management; `/api/v1/me`; CORS allow-list; stable API error codes
  mapped to Turkish messages.

**Exit:** a user can create a case; a second user cannot see it until added.
*Met:* verified by API integration tests and a browser walk-through
(create → edit → add viewer → viewer read-only → outsider gets not-found).
MFA enforcement for privileged roles is configured at the identity provider and
tracked under T6.

### T1 — Ingestion
- Upload session → presigned PUT to private bucket → finalise endpoint.
- Server-side SHA-256, content-type sniffing (Tika), size limit, encrypted/corrupt
  detection, duplicate-by-hash policy.
- Tables: `documents`, `document_versions`, `pages`, `processing_jobs`.
- Worker: PDFBox per-page text, printed-page-label capture (PDF page labels +
  header/footer heuristics), OCR fallback for low-text pages stored in a separate
  column, per-page status and quality score, retry with bounded backoff.
- UI: document library with per-page processing status; failed-page list + reprocess.

**Exit:** the 870-page TBMM Volume 2 PDF ingests; every page has a row with
status, method and quality.

### T2 — Retrieval & source viewer
- Chunker v1: within page, paragraph-aware, ~400–800 tokens, speaker detection
  for hearing transcripts, cross-page chunks keep all page refs.
- `text_segments` + PostgreSQL FTS (`simple` + unaccent config tuned for Turkish)
  + pgvector embeddings.
- Hybrid search (lexical + vector, RRF fusion), exact-phrase priority, filters.
- Document viewer: PDF page render, PDF number vs printed label, highlighted
  passage, OCR warnings, "report an error".

**Exit:** curated questions return the expected page in top-k (target ≥ 95%).

### T3 — Source-grounded Q&A
- Pipeline: scope → retrieve → rerank → delimited context package → generate
  with claim/citation JSON → validate every citation ID against retrieved set →
  unsupported-claim check → abstain if weak.
- Prompt-injection defences: source text in delimited data blocks, instructions
  never sourced from documents, injection fixtures in eval set.
- `answers`, `citations` stored with full model/retrieval config.
- Evaluation harness in `evals/` run in CI on prompt/model change.

**Exit:** benchmark citation correctness ≥ 95%, 0 critical unsupported claims.

### T4 — Investigation workflow
- Statements (speaker, exact passage, qualifiers, proposition), statement
  comparisons with the 7 TR §7.2 categories, findings with BR §9 labels, review
  workflow (Reviewer role), research leads incl. the 4 missing-document states,
  annotations.
- Candidate entity/event extraction with mention-level provenance (no
  name-similarity merges).

**Exit:** compare two statements and save a review; create and track a
missing-document lead.

### T5 — Reporting & audit
- Report builder (BR §10 sections) → Markdown + PDF export, draft/reviewed/approved
  banner, every claim footnoted to document + page.
- Audit log viewer for Case Admins.

**Exit:** exported report is fully source-traceable.

### T6 — Pilot hardening
- MFA enforcement for privileged roles, rate limiting, malware-scan hook,
  dependency/container scanning, OpenTelemetry, load tests against TR §13
  targets, backup/restore drill, accessibility pass.

## 5. Immediate next steps
1. Create the GitHub repository and push the T0 scaffold.
2. Confirm or override the working defaults in §1.
3. Obtain the TBMM Volume 2 PDF and draft the first 20 benchmark questions
   (BR §8.3 page references are a good seed).
4. Start T1.
