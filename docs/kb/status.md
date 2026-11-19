# Status

_Last updated: 2026-11-19 (T3 source-grounded Q&A)_

| Milestone | State | Notes |
|---|---|---|
| T0 Foundation | Done | OIDC login, cases, RBAC, members, append-only audit |
| T1 Ingestion | Done* | *Not yet validated on the real TBMM Volume 2 PDF |
| T2 Retrieval & viewer | Done* | *Real bge-m3 never run here (no model download); bake-off pending |
| Spring Boot 4 migration | Done | Boot 4.1.2, Spring 7.0.10, Security 7.1, Jackson 3, Kotlin 2.3.21; no accepted CVEs left |
| Security pipeline | Done (first pass) | gitleaks, Trivy + npm audit, 13 security tests, web headers; see `security.md` |
| T3 Source-grounded Q&A | Done* | *ADR 0002 option A accepted for the demo (public corpus); default stays `none`. Tested with a scripted model only, never yet against the real model or the real PDF |
| T4 Investigation workflow | Planned | Statements, comparisons, findings, leads |
| T5 Reporting & audit UI | Planned | |
| T6 Pilot hardening | Planned | MFA, rate limiting, malware scan, image scanning, load tests |

## Waiting on the product owner
- Set `LLM_PROVIDER=anthropic` and `ANTHROPIC_API_KEY` in the demo environment (ADR 0002 option A accepted).
- Before any private case material: decide ADR 0002 option B or C.
- Install the Renovate GitHub App on the repository (config is in `renovate.json`); Issues must be enabled for the Dependency Dashboard.
- The TBMM Volume 2 PDF (T1 validation, eval questions, embedding bake-off).
- Pinned bge-m3 revision (`EMBEDDINGS_MODEL_REVISION`) for shared environments.

## Known debt
- Local API tests and the local dev app share the `coldcase` database; tests leave queued jobs whose files are gone,
  and a running dev worker retries them (harmless warnings). Give tests their own database.
- Context cycle `documents ↔ processing ↔ indexing`; `indexing → search` for `TurkishText`.
  See `domain/context-map.md` §Known debt. Pinned by `ContextBoundariesTest`.
- No Dockerfiles yet, so no container image scanning (T6).
- No rate limiting (T6). No malware scanning of uploads (T6). No SAST yet (CodeQL needs GitHub Advanced Security
  on a private repo; Semgrep is the alternative). Web CSP still allows `'unsafe-inline'` scripts (Next.js needs nonces).
- Page-image highlighting of cited passages (only text highlighting exists).
- Presigned direct-to-bucket uploads not implemented (multipart through the API).
- Real Keycloak not yet exercised by an automated test (no Docker in the agent environment); the S3 path is tested
  in CI against SeaweedFS (`S3ObjectStoreIntegrationTest`; CI fails if those tests are skipped).
- Q&A: no answer-quality evaluation yet (needs the real PDF + eval questions in `evals/`); no streaming of answers
  (a question takes up to a minute); citation offsets point into the page text as it was when asked — re-OCR of
  that page can shift them (the stored quote stays correct).
- Q&A has no per-user rate limit or cost cap yet (T6).
- Embedding server has no native arm64 image: Apple Silicon developers run it emulated or natively via TEI's Metal build.
