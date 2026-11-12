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
- **Real people in fixtures:** early fixtures put invented quotes in real witnesses' mouths. Use fictional names.
