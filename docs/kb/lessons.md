# Lessons and gotchas

Things that cost time once. Add to this list whenever something surprises you.

- **Turkish case folding:** PostgreSQL `lower('YILMAZ')` gives `yilmaz`, but `Yılmaz` lowers to `yılmaz`, so all-caps
  transcript names don't match body text. Use `coldcase_fold()` / `TurkishText.fold` (translate *before* lower).
- **Tesseract Turkish model** reads ASCII-typed `OZDEMIR` as `OZDEMİR`; case-insensitive matching doesn't fold
  `İ`. Compare folded text in tests.
- **Vector search slowness:** passing the query vector as a text parameter inside `ORDER BY` re-parses it per row
  (900 ms → 40 ms after moving it into a `MATERIALIZED` CTE).
- **Stale planner statistics** right after indexing thousands of segments made the first searches slow; the indexer
  runs `ANALYZE` after each document.
- **pgvector < 0.7 isn't a trusted extension:** `CREATE EXTENSION vector` needs a superuser outside the compose image.
- **Running as root:** `File.canWrite()` is always true; check POSIX permissions instead.
- **Headless Chromium** never fires `load` for a PDF tab, and a `noopener` popup hides its URL from Playwright —
  assert on the network response instead.
- **Next.js route announcer** has `role="alert"`; target your own error element (`.error`) in tests.
- **Maven Central 429s** through the build proxy: retry without `--refresh-dependencies`, use `--max-workers=1`.
- **OCRmyPDF's Python bindings** were broken on the build image; running Tesseract directly per page is simpler anyway.
- **Stale test reports:** if Gradle fails to resolve dependencies (Maven 429), no tests run but old XML in
  `build/test-results` still looks green. Delete `build/test-results` or check Gradle's exit code.
- **`npm audit | tail` hides the exit code;** run it unpiped (or with `set -o pipefail`) when gating.
- **Pinned web framework versions age fast:** Next.js 15.5.4 had a critical RCE within weeks. The weekly
  security workflow exists for this; act on it.
- **First Trivy run** found 17 HIGH/CRITICAL in transitive Java deps of a year-old Spring Boot pin; Bouncy Castle
  findings came only from the Kotlin plugin's own classpath, which is why locking covers shipped classpaths only.
- **`docker compose up` on Apple Silicon:** one image without an arm64 build (TEI CPU) aborts *all* pulls, so the other
  images show as "Interrupted". Check platforms with the registry API before pinning an image; amd64-only services go
  behind a profile with `platform: linux/amd64`.
- **MinIO no longer publishes community container images** (Docker Hub repo gone); the dev stack uses SeaweedFS.
- **Running Renovate locally** (`--platform=local --dry-run=extract`) needs Node 24 and only sees *committed* files —
  an uncommitted `renovate.json` is silently ignored.
- **Spring Boot 4 migration:** starters are per module (`spring-boot-starter-webmvc`,
  `-security-oauth2-resource-server`, `-flyway`, `-micrometer-metrics`, test: `-webmvc-test`, `-security-test`);
  `AutoConfigureMockMvc` moved to `org.springframework.boot.webmvc.test.autoconfigure`; multipart request builders no
  longer extend `MockHttpServletRequestBuilder`. After changing dependencies, a "not part of the dependency lock state"
  error means: run `./gradlew dependencies --write-locks` — it is not a download failure.
- **Jackson 3 `JsonNode.map`** silently changed meaning in Kotlin (member beats extension); some calls still compiled
  with a different result type (`String` instead of `List`). Grep for `].map {` on JSON nodes when upgrading.
- **Real people in fixtures:** early fixtures put invented quotes in real witnesses' mouths. Use fictional names.
- **Anthropic Java SDK 3.x** sends the API key as `Authorization: Bearer`, not `x-api-key`; tests that stub the
  Messages API should accept either. It also pulls in OkHttp 4 and Jackson 2 (`com.fasterxml`) alongside Jackson 3 —
  keep vendor JSON types inside the `llm` adapter.
- **PostgreSQL isn't started automatically** in a fresh agent container: `service postgresql start` before `./gradlew test`
  (symptom: every Spring test fails with "Failed to load ApplicationContext").
- **Structured outputs** reject `minLength`/`maxLength`/numeric limits and need `additionalProperties: false` on every
  object; enforce lengths in code (`CitationValidator`) instead.
- **Short fixture pages go to OCR:** a page with fewer than 25 non-whitespace characters (`app.extraction.min-text-chars`)
  is sent to Tesseract. The Security workflow has no Tesseract, so such a page fails there and has no segments, while
  it passes locally. Give fixtures that need searchable text a full sentence; reproduce with `TESSERACT_CMD=/nonexistent`.
