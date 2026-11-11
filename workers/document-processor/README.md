# Document processor

The processing worker ships in the API artifact (`services/api`, package `tr.coldcase.api.processing`)
rather than as a separate codebase, so it shares the schema and domain code without duplication.

- Jobs live in the `processing_jobs` table and are claimed with `FOR UPDATE SKIP LOCKED`
  (`JobQueue`), so any number of workers can run concurrently. Expired leases are reclaimed.
- `WORKER_ENABLED=true` (default) runs the worker inside the API process — fine for local dev.
- To scale independently, deploy the same image twice: API replicas with `WORKER_ENABLED=false`,
  worker replicas with `WORKER_ENABLED=true` (no ingress).

Pipeline per document version:

1. `EXTRACT_DOCUMENT` — download the original, verify its SHA-256, extract embedded text per
   page (PDFBox), score text quality, detect printed page numbers, flag empty/garbled pages.
2. `OCR_PAGES` (batches of 10) — render flagged pages at 300 DPI and OCR them with Tesseract
   (`tur`). OCR text and confidence are stored separately from embedded text.

Transient failures retry with exponential backoff (5 s · 2^n, max 10 min, 5 attempts).
Integrity failures, unreadable PDFs and a missing OCR engine fail immediately.
