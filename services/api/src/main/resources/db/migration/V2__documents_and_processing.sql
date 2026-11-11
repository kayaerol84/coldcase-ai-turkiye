-- T1 ingestion: documents, immutable versions, per-page extraction, processing jobs.
-- pdf_page_number is 1-based. Printed page labels are stored separately and never assumed equal.

CREATE TABLE documents (
    id           UUID PRIMARY KEY,
    case_id      UUID NOT NULL REFERENCES cases(id),
    title        TEXT NOT NULL CHECK (length(title) BETWEEN 1 AND 500),
    source_name  TEXT,
    source_date  DATE,
    language     VARCHAR(10) NOT NULL DEFAULT 'tr',
    -- PROCESSING | READY | READY_WITH_WARNINGS | FAILED
    status       VARCHAR(30) NOT NULL,
    status_detail TEXT,
    created_by   UUID NOT NULL REFERENCES users(id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_documents_case ON documents(case_id, created_at DESC);

CREATE TABLE document_versions (
    id               UUID PRIMARY KEY,
    case_id          UUID NOT NULL REFERENCES cases(id),
    document_id      UUID NOT NULL REFERENCES documents(id),
    object_key       TEXT NOT NULL,
    sha256           CHAR(64) NOT NULL,
    mime_type        TEXT NOT NULL,
    file_size_bytes  BIGINT NOT NULL,
    original_filename TEXT,
    page_count       INTEGER,
    pdf_metadata     JSONB NOT NULL DEFAULT '{}',
    uploaded_by      UUID NOT NULL REFERENCES users(id),
    uploaded_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (document_id, sha256)
);
-- Duplicate detection within a case (policy: reject, pointing at the existing document).
CREATE UNIQUE INDEX uq_document_versions_case_sha ON document_versions(case_id, sha256);

CREATE TABLE pages (
    id                   UUID PRIMARY KEY,
    case_id              UUID NOT NULL REFERENCES cases(id),
    document_id          UUID NOT NULL REFERENCES documents(id),
    document_version_id  UUID NOT NULL REFERENCES document_versions(id),
    pdf_page_number      INTEGER NOT NULL CHECK (pdf_page_number >= 1),
    printed_page_label   TEXT,
    -- PDF_PAGE_LABELS | HEADER_FOOTER | NULL when unknown
    printed_label_source VARCHAR(30),
    -- PENDING | EXTRACTED | NEEDS_OCR | OCR_DONE | FAILED
    extraction_status    VARCHAR(30) NOT NULL,
    -- EMBEDDED_TEXT | OCR
    extraction_method    VARCHAR(30),
    extraction_quality   NUMERIC(5,4),
    embedded_text        TEXT,
    ocr_text             TEXT,
    ocr_engine           VARCHAR(60),
    ocr_engine_version   VARCHAR(60),
    ocr_confidence       NUMERIC(5,4),
    extractor_version    VARCHAR(60) NOT NULL,
    error                TEXT,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (document_version_id, pdf_page_number)
);
CREATE INDEX idx_pages_document ON pages(document_id, pdf_page_number);

-- Postgres-backed job queue (claimed with FOR UPDATE SKIP LOCKED).
CREATE TABLE processing_jobs (
    id                   UUID PRIMARY KEY,
    case_id              UUID NOT NULL REFERENCES cases(id),
    document_version_id  UUID NOT NULL REFERENCES document_versions(id),
    -- EXTRACT_DOCUMENT | OCR_PAGES
    job_type             VARCHAR(30) NOT NULL,
    payload              JSONB NOT NULL DEFAULT '{}',
    -- QUEUED | RUNNING | SUCCEEDED | FAILED
    status               VARCHAR(20) NOT NULL,
    attempts             INTEGER NOT NULL DEFAULT 0,
    max_attempts         INTEGER NOT NULL DEFAULT 5,
    run_after            TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_by            TEXT,
    locked_at            TIMESTAMPTZ,
    last_error           TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at           TIMESTAMPTZ,
    finished_at          TIMESTAMPTZ
);
CREATE INDEX idx_jobs_claim ON processing_jobs(status, run_after) WHERE status IN ('QUEUED', 'RUNNING');
CREATE INDEX idx_jobs_version ON processing_jobs(document_version_id);
