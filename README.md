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
cd apps/web && npm install && npm run dev
```

- API: http://localhost:8080 (OpenAPI at `/api/openapi`, Swagger UI at `/swagger-ui.html`)
- Keycloak: http://localhost:8180 (realm `coldcase`, demo users `researcher` / `reviewer`)
- MinIO console: http://localhost:9001

Get a dev token:

```bash
curl -s -d client_id=coldcase-web -d grant_type=password -d username=researcher -d password=researcher http://localhost:8180/realms/coldcase/protocol/openid-connect/token
```

## Tests

API tests need a PostgreSQL at `DB_URL` (defaults to `localhost:5432/coldcase`, user/password `coldcase`):

```bash
cd services/api && ./gradlew test
```

## Status

Milestone **T0 — Foundation** in progress: case workspaces, case-level RBAC, append-only audit log.
