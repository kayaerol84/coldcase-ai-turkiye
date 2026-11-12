# Security

Sensitive material: historical case records, personal data, unverified allegations. Defaults are private,
least privilege, auditable. Requirements: TR §12, §16, §17.

## Threats and controls

| Threat | Control | Where | Verified by |
|---|---|---|---|
| Cross-case access / IDOR | One authz choke point; 404 for non-members; SQL always filtered by `case_id` | `CaseAccessService` | `AuthorizationMatrixTest` (every endpoint, discovered automatically) |
| Forged / foreign tokens | JWT resource server: signature, expiry, issuer **and audience** (`coldcase-api`) validated | `SecurityConfig` | `TokenValidationTest` (real decoder against a local JWKS) |
| Missing auth on a new endpoint | Deny by default; matrix test fails if an endpoint has no fixture | `SecurityConfig`, `AuthorizationMatrixTest` | CI `security-tests` |
| API schema reconnaissance | OpenAPI/Swagger require login unless `PUBLIC_API_DOCS=true` (local dev) | `SecurityConfig` | `AuthorizationMatrixTest` |
| Malicious uploads | Content sniffing, PDF parse, encrypted/corrupt/oversize/page-limit rejection; stored by hash; served with `nosniff` + attachment by default | `PdfInspector`, `DocumentController.file` | `UploadSecurityTest`, `DocumentApiTest` |
| Tampered originals | SHA-256 verified before every processing run and render | `DocumentProcessor`, `OriginalCache` | `DocumentApiTest` |
| Data leakage in logs | Never log text, queries, prompts, tokens | all | `LogLeakageTest` (canary) |
| Error detail leakage | ProblemDetail with stable codes; no stack traces | `ApiExceptionHandler` | `SecurityHeadersTest` |
| Clickjacking / MIME sniffing / caching of case data | `X-Frame-Options: DENY`, CSP `frame-ancestors 'none'`, `nosniff`, `Cache-Control: no-store` on API | `SecurityConfig`, `apps/web/next.config.ts` | `SecurityHeadersTest`, `apps/web/scripts/check-headers.mjs` |
| CORS abuse | Explicit origin allow-list | `SecurityConfig` | `CaseApiTest` |
| Mass assignment | Request DTOs only carry editable fields | controllers | `AuthorizationMatrixTest` (PATCH ignores ids) |
| SQL / tsquery / LIKE injection | Bound parameters only; LIKE escaping; tsquery built from `[letters/digits]` tokens | `SearchService` | `SearchApiTest` (hostile queries) |
| Embedding model swap / supply chain | Pinned image digest + model revision; `/info` check | `TeiEmbeddingProvider`, compose | `RetrievalUnitTest` |
| Vector inversion | Vectors treated as sensitive as text; never returned by the API | `segment_embeddings` | review |
| Leaked secrets in git | gitleaks on full history | `security.yml` | CI `secrets` |
| Vulnerable dependencies | Trivy on Gradle lockfile + npm lockfile; `npm audit`; patched Next.js 15.5.28 / React 19.1.10, `sharp`/`postcss` overrides; Netty async client excluded from the AWS SDK | `security.yml`, `apps/web/package.json`, `services/api/build.gradle.kts` | CI `dependencies` (weekly + every push) |
| Image optimiser exploits (Next.js) | `images.unoptimized` — `/_next/image` not served | `apps/web/next.config.ts` | manual (404) |
| Prompt injection via documents (T3) | Delimited source blocks, no instructions from sources, citation validation | T3 | planned |
| Brute force / DoS | Query length caps; rate limiting planned | `SearchController` | T6 |

## Rules for contributors
- Every new endpoint: authorise through `CaseAccessService`, add a fixture to `AuthorizationMatrixTest`,
  return stable error codes.
- Never put document text, search queries, prompts or tokens in logs, metrics labels, audit details or exceptions.
- Never send case content to an external service unless an ADR allows it (currently: none for embeddings;
  the LLM provider for T3 needs its own decision on what is sent).
- New third-party code: pin versions (images by digest), and keep the lockfiles committed.
- Test data uses fictional people.

## Pipeline (`.github/workflows/security.yml`)
| Job | Tool | Fails on |
|---|---|---|
| `secrets` | gitleaks (pinned image) over full git history | any finding |
| `dependencies` | Trivy fs scan of `services/api/gradle.lockfile`, `apps/web/package-lock.json`; `npm audit` | HIGH/CRITICAL with a fix available |
| `security-tests` | `./gradlew securityTest` (JUnit tag `security`): `AuthorizationMatrixTest`, `TokenValidationTest`, `UploadSecurityTest`, `SecurityHeadersTest`, `LogLeakageTest` in `services/api/src/test/kotlin/tr/coldcase/api/security/` | any failure |
| `web-headers` | build + start Next.js, check response headers | missing header |

The matrix and token tests were mutation-checked: removing the authz call from one endpoint, or the audience
setting, makes them fail with a precise message.

## Accepted risks (`.trivyignore.yaml`, each with an expiry date)
| Finding | Why accepted | Expires |
|---|---|---|
| CVE-2026-47884 (Spring Framework 6.2, RCE via `XsltView`) | No open-source fix for 6.2; the API has no XSLT views or view resolvers | 2026-12-31 |
| CVE-2026-47890 (Spring Framework 6.2, SSE with views) | No server-sent events, no views | 2026-12-31 |
Both are fixed in Spring 7 → **Spring Boot 4 migration must land before 2026-12-31**, when the scanner fails again.
Patched transitive versions pinned in `services/api/build.gradle.kts` (`tomcat.version`, `jackson-bom.version`,
`postgresql.version`) — remove the overrides when the BOM catches up.

Not yet covered: container image scanning (no Dockerfiles yet), SAST (CodeQL needs GitHub Advanced Security on
private repos; Semgrep is an option), DAST, rate-limit tests, malware scanning — see `status.md`.
