# Status

_Last updated: 2026-11-12 (security pipeline, DDD skills, KB)_

| Milestone | State | Notes |
|---|---|---|
| T0 Foundation | Done | OIDC login, cases, RBAC, members, append-only audit |
| T1 Ingestion | Done* | *Not yet validated on the real TBMM Volume 2 PDF |
| T2 Retrieval & viewer | Done* | *Real bge-m3 never run here (no model download); bake-off pending |
| Security pipeline | Done (first pass) | gitleaks, Trivy + npm audit, 13 security tests, web headers; see `security.md` |
| T3 Source-grounded Q&A | Next | Citation validation, abstention, prompt-injection defences |
| T4 Investigation workflow | Planned | Statements, comparisons, findings, leads |
| T5 Reporting & audit UI | Planned | |
| T6 Pilot hardening | Planned | MFA, rate limiting, malware scan, image scanning, load tests |

## Waiting on the product owner
- The TBMM Volume 2 PDF (T1 validation, eval questions, embedding bake-off).
- Pinned bge-m3 revision (`EMBEDDINGS_MODEL_REVISION`) for shared environments.

## Known debt
- **Spring Boot 3.5 line → Boot 4 migration due by 2026-12-31.** 3.5.16 is the last 3.5 patch; two Spring
  Framework 6.2 CVEs have no open-source fix and are accepted only until then (`.trivyignore.yaml`).
  Tomcat/Jackson/Postgres driver are pinned to patched versions in `services/api/build.gradle.kts`.
- Context cycle `documents ↔ processing ↔ indexing`; `indexing → search` for `TurkishText`.
  See `domain/context-map.md` §Known debt. Pinned by `ContextBoundariesTest`.
- No Dockerfiles yet, so no container image scanning (T6).
- No rate limiting (T6). No malware scanning of uploads (T6). No SAST yet (CodeQL needs GitHub Advanced Security
  on a private repo; Semgrep is the alternative). Web CSP still allows `'unsafe-inline'` scripts (Next.js needs nonces).
- Page-image highlighting of cited passages (only text highlighting exists).
- Presigned direct-to-bucket uploads not implemented (multipart through the API).
- MinIO/S3 storage path and real Keycloak never exercised in this build environment (no Docker).
