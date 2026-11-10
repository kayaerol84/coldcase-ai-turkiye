# Document processor (T1)

Asynchronous worker for PDF ingestion: per-page text extraction (PDFBox), OCR fallback
(OCRmyPDF + Tesseract `tur`), printed-page-label capture, chunking and embedding.

Consumes jobs from the `processing_jobs` table (`FOR UPDATE SKIP LOCKED`) behind a
`JobQueue` interface so it can move to RabbitMQ/SQS later. Scales independently of the API.

Not implemented yet — see `docs/PLAN.md`, milestone T1.
