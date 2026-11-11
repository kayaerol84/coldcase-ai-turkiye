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

- API: http://localhost:8080 (OpenAPI at `/api/openapi`, Swagger UI at `/swagger-ui.html`)
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

Key settings (environment variables): `MAX_UPLOAD_SIZE` (500MB), `MAX_PDF_PAGES` (5000),
`WORKER_ENABLED` (true), `OCR_LANGUAGES` (`tur`), `S3_*`, `S3_SSE` (enable in production).

## Tests

API tests need a PostgreSQL at `DB_URL` (defaults to `localhost:5432/coldcase`, user/password `coldcase`):

```bash
cd services/api && ./gradlew test
```

## Status

- **T0 — Foundation** complete: OIDC login, case workspaces, case-level RBAC and member
  management, append-only audit log.
- **T1 — Ingestion** complete pending validation on the real corpus: PDF upload with content
  validation and SHA-256, immutable originals, asynchronous page-level extraction, Tesseract OCR
  fallback, printed page labels, retries, reprocessing, document library and page inspector.

Next: **T2 — Retrieval & source viewer** (see `docs/PLAN.md`).
