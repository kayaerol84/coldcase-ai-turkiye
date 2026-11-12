# ColdCase AI Türkiye

A source-grounded investigative research platform for large historical case-document
collections. It helps researchers search, compare accounts, track missing documents and
produce traceable reports. **It does not determine guilt or identify perpetrators**; AI
output is a draft until a human reviews it.

- Requirements: [`docs/business_requirements.md`](docs/business_requirements.md),
  [`docs/technical_requirements.md`](docs/technical_requirements.md)
- Build plan & milestones: [`docs/PLAN.md`](docs/PLAN.md)

## Layout

| Path | What |
|---|---|
| `apps/web` | Next.js + TypeScript research workspace |
| `services/api` | Kotlin + Spring Boot modular monolith (REST `/api/v1`) |
| `workers/document-processor` | PDF extraction / OCR / indexing worker (T1) |
| `packages/api-contracts` | OpenAPI contract |
| `infra/docker` | Local stack: PostgreSQL+pgvector, MinIO, Keycloak |
| `evals` | Retrieval & citation benchmark |

## Local development

```bash
docker compose -f infra/docker/docker-compose.yml up -d
```

```bash
cd services/api && ./gradlew bootRun
```

```bash
cd apps/web && cp .env.example .env.local && npm install && npm run dev
```

- API: http://localhost:8080 (OpenAPI at `/api/openapi`, Swagger UI at `/swagger-ui.html` — set `PUBLIC_API_DOCS=true` to open them without login)
- Health & Prometheus metrics: http://localhost:8081/actuator/health, `/actuator/prometheus` (internal port)
- Web: http://localhost:3000 — sign in as `researcher` / `researcher` or `reviewer` / `reviewer`
- Keycloak: http://localhost:8180 (realm `coldcase`)
- MinIO console: http://localhost:9001

Get a dev token:

```bash
curl -s -d client_id=coldcase-web -d grant_type=password -d username=researcher -d password=researcher http://localhost:8180/realms/coldcase/protocol/openid-connect/token
```

### Document storage and OCR

By default originals are stored on local disk under `services/api/data/objects`. To use MinIO from
the compose stack instead:

```bash
STORAGE_TYPE=s3 ./gradlew bootRun
```

Scanned pages are OCR'd with Tesseract (Turkish). Install it locally for OCR to work; without it,
scanned pages are flagged "OCR gerekli" and the document is marked ready with warnings.

```bash
sudo apt-get install tesseract-ocr tesseract-ocr-tur
```

### Search and embeddings

Keyword and exact-phrase search work out of the box. Semantic search needs the self-hosted embedding
server from the compose stack (`embeddings`, BAAI/bge-m3; the first start downloads the model, ~2.3 GB):

```bash
EMBEDDINGS_PROVIDER=tei EMBEDDINGS_API_KEY=dev-embed-key ./gradlew bootRun
```

Pin the model in every non-local environment: set `EMBEDDINGS_MODEL_REVISION` to the model's
Hugging Face commit hash for both the embedding server and the API. The API checks the server's
reported model and revision and disables semantic search on a mismatch. Embeddings are never sent to
an external API (see `docs/adr/0001-embedding-model.md`).

PostgreSQL needs the `vector` and `pg_trgm` extensions. The compose image has them; elsewhere a
database administrator may need to run `CREATE EXTENSION vector;` once before migrations.

Key settings (environment variables): `MAX_UPLOAD_SIZE` (500MB), `MAX_PDF_PAGES` (5000),
`WORKER_ENABLED` (true), `OCR_LANGUAGES` (`tur`), `S3_*`, `S3_SSE` (enable in production),
`EMBEDDINGS_PROVIDER` (`none`|`tei`), `EMBEDDINGS_URL`, `EMBEDDINGS_API_KEY`, `EMBEDDINGS_MODEL_REVISION`.

## Working on this repo

Read `CLAUDE.md` and the knowledge base in `docs/kb/` first (status, architecture, domain language, context map,
invariants, security). Keep the knowledge base current in the same change.

## Tests

API tests need a PostgreSQL at `DB_URL` (defaults to `localhost:5432/coldcase`, user/password `coldcase`):

```bash
cd services/api && ./gradlew test
```

```bash
cd services/api && ./gradlew securityTest
```

## Status

- **T0 — Foundation** complete: OIDC login, case workspaces, case-level RBAC and member
  management, append-only audit log.
- **T1 — Ingestion** complete pending validation on the real corpus: PDF upload with content
  validation and SHA-256, immutable originals, asynchronous page-level extraction, Tesseract OCR
  fallback, printed page labels, retries, reprocessing, document library and page inspector.

- **T2 — Retrieval & source viewer** complete pending the bake-off on the real corpus: speaker-aware
  page-bounded chunking, Turkish full-text search (stemming + diacritic folding), exact phrase,
  self-hosted bge-m3 semantic search, hybrid ranking, filters, page viewer with the rendered original
  and highlighted passage, page problem reports.

Next: **T3 — Source-grounded Q&A** (see `docs/PLAN.md`).
