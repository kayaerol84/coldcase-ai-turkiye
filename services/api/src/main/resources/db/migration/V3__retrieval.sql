-- T2 retrieval: page-bounded text segments, Turkish-aware full-text search, embeddings, page reports.
-- The `vector` extension must be created by a database administrator where it is not a trusted extension.
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Folds Turkish letters to ASCII and lowercases, character for character (offsets are preserved).
-- translate() runs before lower() so dotted/dotless I are folded correctly regardless of locale.
-- Lets "Ozdemir" match "ÖZDEMİR" and "YILMAZ" match "Yılmaz".
CREATE FUNCTION coldcase_fold(t text) RETURNS text
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
    AS $$ SELECT lower(translate(t, 'İIıÂâÎîÛûÇçĞğÖöŞşÜü', 'iiiaaiiuuccggoossuu')) $$;

CREATE TABLE text_segments (
    id                   UUID PRIMARY KEY,
    case_id              UUID NOT NULL REFERENCES cases(id),
    document_id          UUID NOT NULL REFERENCES documents(id),
    document_version_id  UUID NOT NULL REFERENCES document_versions(id),
    page_id              UUID NOT NULL REFERENCES pages(id),
    pdf_page_number      INTEGER NOT NULL,
    segment_index        INTEGER NOT NULL,
    text                 TEXT NOT NULL,
    -- Offsets into the page's source text (embedded or OCR, see source_kind).
    char_start           INTEGER NOT NULL,
    char_end             INTEGER NOT NULL,
    source_kind          VARCHAR(20) NOT NULL,
    speaker              TEXT,
    content_sha256       CHAR(64) NOT NULL,
    token_estimate       INTEGER NOT NULL,
    chunker_version      VARCHAR(40) NOT NULL,
    metadata             JSONB NOT NULL DEFAULT '{}',
    -- Turkish stems for morphology plus folded tokens for diacritic-insensitive matching.
    search_tsv           tsvector GENERATED ALWAYS AS
                             (to_tsvector('turkish', text) || to_tsvector('simple', coldcase_fold(text))) STORED,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (char_end > char_start),
    UNIQUE (document_version_id, segment_index)
);
CREATE INDEX idx_segments_case ON text_segments(case_id);
CREATE INDEX idx_segments_page ON text_segments(page_id);
CREATE INDEX idx_segments_tsv ON text_segments USING gin (search_tsv);
CREATE INDEX idx_segments_trgm ON text_segments USING gin (coldcase_fold(text) gin_trgm_ops);

-- One row per segment per embedding model, so a new model can be backfilled and compared
-- before switching. Vector search is an exact scan within one case (no ANN index yet):
-- per-case corpora are small, and filtering before ranking keeps case isolation exact.
CREATE TABLE segment_embeddings (
    segment_id      UUID NOT NULL REFERENCES text_segments(id) ON DELETE CASCADE,
    case_id         UUID NOT NULL REFERENCES cases(id),
    model_id        TEXT NOT NULL,
    model_revision  TEXT,
    dims            INTEGER NOT NULL,
    content_sha256  CHAR(64) NOT NULL,
    embedding       vector NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (segment_id, model_id)
);
CREATE INDEX idx_embeddings_case_model ON segment_embeddings(case_id, model_id);

ALTER TABLE document_versions
    ADD COLUMN index_status    VARCHAR(20) NOT NULL DEFAULT 'NOT_INDEXED', -- NOT_INDEXED | INDEXED | FAILED
    ADD COLUMN indexed_at      TIMESTAMPTZ,
    ADD COLUMN chunker_version VARCHAR(40),
    ADD COLUMN embedding_model TEXT,
    ADD COLUMN segment_count   INTEGER;

-- At most one queued indexing job per version (several finishing jobs may each try to enqueue one).
CREATE UNIQUE INDEX uq_jobs_pending_index ON processing_jobs(document_version_id)
    WHERE job_type = 'INDEX_DOCUMENT' AND status = 'QUEUED';

-- Researcher reports of extraction / OCR / page-label problems (TR §11.2).
CREATE TABLE page_reports (
    id           UUID PRIMARY KEY,
    case_id      UUID NOT NULL REFERENCES cases(id),
    document_id  UUID NOT NULL REFERENCES documents(id),
    page_id      UUID NOT NULL REFERENCES pages(id),
    kind         VARCHAR(30) NOT NULL CHECK (kind IN ('EXTRACTION_ERROR','OCR_ERROR','WRONG_PAGE_LABEL','MISSING_TEXT','OTHER')),
    note         TEXT CHECK (length(note) <= 2000),
    status       VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    created_by   UUID NOT NULL REFERENCES users(id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_page_reports_document ON page_reports(document_id, created_at DESC);
