# Context map

Bounded contexts and how they may depend on each other. Enforced by
`services/api/src/test/kotlin/tr/coldcase/api/ContextBoundariesTest.kt` — that test and this page change together.

## Contexts

| Context | Packages | Owns (aggregates / tables) | Upstream of |
|---|---|---|---|
| Identity & Access | `identity`, `cases` | User; Case (+ memberships) — `users`, `cases`, `case_memberships` | everything case-scoped |
| Source Ingestion | `documents`, `processing` | Document (+ versions, pages, page reports, jobs) — `documents`, `document_versions`, `pages`, `page_reports`, `processing_jobs` | Retrieval |
| Retrieval | `indexing`, `search` | Segment index — `text_segments`, `segment_embeddings` | Q&A (T3) |
| Q&A (T3, planned) | `qa` | Answer, Citation | Investigation |
| Investigation (T4, planned) | `investigation` | Statement, Comparison, Finding, Lead, Annotation | Reporting |
| Reporting (T5, planned) | `reports` | Report | — |

Shared kernel / infrastructure (no domain logic of their own): `common`, `config`, `audit`, `storage`, `embeddings`.
Adapters to external systems live behind ports: `ObjectStore` (S3/filesystem), `EmbeddingProvider` (TEI),
`OcrEngine` (Tesseract) — anti-corruption layers; no vendor types leak past them.

## Relationships
- Every context is **downstream of Identity & Access** via `CaseAccessService` (conformist: it uses `CaseRole`/`Permission` as-is).
- Retrieval reads Ingestion's page texts and version records (customer/supplier). Retrieval must never write Ingestion tables.
- Q&A will consume Retrieval through `SearchService`-style queries only, never through Retrieval tables.
- Investigation references sources only through **segment IDs + page IDs** (published language), never by copying text without provenance.

## Allowed dependencies (mirror of the test)
```
common, config, audit   -> (none)
storage                 -> config, common
embeddings              -> config
identity                -> audit
cases                   -> audit, common, identity
documents               -> audit, cases, common, config, identity, indexing*, processing*, storage
processing              -> common, config, documents*, embeddings, indexing*, storage
indexing                -> documents*, embeddings, search*
search                  -> cases, common, config, embeddings, identity
```
`*` = known debt, below.

## Known debt
1. **Ingestion ↔ Retrieval cycle.** `documents → indexing` (index info in document responses), `processing → indexing`
   (index job), `indexing → documents` (version record). Fix: Ingestion publishes a "version extracted" event /
   job and Retrieval exposes an `IndexStatusQuery`; move `VersionRecord` access behind a read-only port.
2. **`documents ↔ processing`.** Both are Ingestion, but the service calls the worker's queue and inspector directly.
   Acceptable inside one context; split into `ingestion.domain` / `ingestion.application` / `ingestion.infrastructure` when it grows.
3. **`indexing → search`** only for `TurkishText`. Fix: move Turkish text handling to a shared `text` module.
