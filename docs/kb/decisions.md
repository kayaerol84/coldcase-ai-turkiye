# Decisions

Architectural decisions get an ADR in `docs/adr/`. Smaller ones are logged here (newest first).

## ADRs
- `docs/adr/0001-embedding-model.md` — Accepted: self-hosted embeddings only, BAAI/bge-m3 default, bake-off pending.

## Log
- 2026-11-14 — Dev object store: SeaweedFS 4.51 (multi-arch, S3 API with conditional writes) replaces MinIO; optional services behind compose profiles.
- 2026-11-12 — Accept two Spring 6.2 CVEs (unused features) until 2026-12-31 via `.trivyignore.yaml`; Boot 4 migration before then.
- 2026-11-12 — Gradle locking limited to shipped/test classpaths so scanners don't report build-tool internals.
- 2026-11-12 — OpenAPI schema requires authentication by default (`PUBLIC_API_DOCS` for local dev).
- 2026-11-12 — Next.js image optimisation disabled; Next.js pinned to 15.5.28 (first release outside all advisories), with `sharp`/`postcss` overrides.
- 2026-11-12 — Spring Boot 3.5.16; Netty excluded from the AWS SDK (sync client only). Migration to Boot 4 tracked in `status.md`.
- 2026-11-12 — Security tests are a separate JUnit tag/task (`securityTest`), run in `check` and in `security.yml`.
- 2026-11-12 — Context boundaries enforced by a source-scanning test rather than ArchUnit (no extra dependency, clearer failures).
- 2026-11-12 — API tokens must carry audience `coldcase-api`; Keycloak client gets an audience mapper.
- 2026-11-12 — Security scanners run from pinned container images (gitleaks, Trivy), not third-party GitHub Actions.
- 2026-11-12 — Vector search is an exact per-case scan; no approximate index until a case exceeds ~100k segments.
- 2026-11-11 — OCR via Tesseract per rendered page instead of OCRmyPDF (per-page retries and confidences).
- 2026-11-11 — Processing worker lives in the API artifact; scale by deploying it with `WORKER_ENABLED=true`.
- 2026-11-11 — Actuator only on internal port 8081.
- 2026-11-10 — Postgres-backed job queue (`SKIP LOCKED`) behind `JobQueue` instead of RabbitMQ for the MVP.
- 2026-11-10 — Multi-user with case membership from day one; non-members get 404.
- See `docs/PLAN.md` §1 for the other working defaults.
