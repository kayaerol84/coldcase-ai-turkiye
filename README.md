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
| `infra/docker` | Local stack: PostgreSQL+pgvector, Keycloak; optional SeaweedFS (S3) and TEI embeddings |
| `evals` | Retrieval & citation benchmark |

## Local development

Start PostgreSQL and Keycloak (runs natively on Intel and Apple Silicon):

```bash
docker compose -f infra/docker/docker-compose.yml up -d
```

Optional services are behind compose profiles: `s3` (S3-compatible object store) and `embeddings` (semantic search).

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

Get a dev token:

```bash
curl -s -d client_id=coldcase-web -d grant_type=password -d username=researcher -d password=researcher http://localhost:8180/realms/coldcase/protocol/openid-connect/token
```

### Document storage and OCR

By default originals are stored on local disk under `services/api/data/objects`. To use S3 storage
instead, start the S3-compatible store (SeaweedFS, on `localhost:9000`) and point the API at it:

```bash
docker compose -f infra/docker/docker-compose.yml --profile s3 up -d
```

```bash
STORAGE_TYPE=s3 ./gradlew bootRun
```

The S3 adapter's integration tests run against it when `S3_TEST_ENDPOINT` is set:

```bash
S3_TEST_ENDPOINT=http://localhost:9000 ./gradlew test --tests '*S3ObjectStoreIntegrationTest*'
```

Scanned pages are OCR'd with Tesseract (Turkish). Install it locally for OCR to work; without it,
scanned pages are flagged "OCR gerekli" and the document is marked ready with warnings.

```bash
sudo apt-get install tesseract-ocr tesseract-ocr-tur
```

On macOS:

```bash
brew install tesseract tesseract-lang
```

### Search and embeddings

Keyword and exact-phrase search work out of the box (`EMBEDDINGS_PROVIDER=none`, the default). Semantic
search needs the self-hosted embedding server (BAAI/bge-m3; the first start downloads the model, ~2.3 GB):

```bash
docker compose -f infra/docker/docker-compose.yml --profile embeddings up -d
```

```bash
EMBEDDINGS_PROVIDER=tei EMBEDDINGS_API_KEY=dev-embed-key ./gradlew bootRun
```

The embedding server image (Hugging Face Text Embeddings Inference, CPU) is published for amd64 only.
On Apple Silicon it runs under emulation: enable "Use Rosetta for x86_64/amd64 emulation on Apple Silicon" in
Docker Desktop. That works but is slow for indexing large documents. For day-to-day work on a Mac, keyword search
(`EMBEDDINGS_PROVIDER=none`) is usually enough; alternatively run TEI natively with its Metal build (see the TEI
documentation) on port 8082 with `--api-key dev-embed-key`.

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

Dependencies are kept up to date by Renovate (`renovate.json`): it opens update PRs weekly and security fixes
immediately; each PR runs CI and the Security workflow. Major upgrades wait for approval on the Dependency Dashboard issue.

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
