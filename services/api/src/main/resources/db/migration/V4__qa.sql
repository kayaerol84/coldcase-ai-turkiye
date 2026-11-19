-- T3 source-grounded Q&A: AI-drafted answers, their claims, and citations that snapshot their source.
-- Citations deliberately do not reference text_segments with a foreign key: re-indexing replaces segments,
-- and an answer must stay readable and traceable afterwards. The snapshot (document, page, labels, quote,
-- offsets into the page text) is the provenance; text_segment_id is kept as a reference that may go stale.

CREATE TABLE answers (
    id              UUID PRIMARY KEY,
    case_id         UUID NOT NULL REFERENCES cases(id),
    asked_by        UUID NOT NULL REFERENCES users(id),
    question        TEXT NOT NULL CHECK (length(question) BETWEEN 1 AND 1000),
    filters         JSONB NOT NULL DEFAULT '{}',
    outcome         VARCHAR(20) NOT NULL CHECK (outcome IN ('ANSWERED', 'ABSTAINED')),
    -- AI output is a draft until a human reviews it (review workflow arrives with T4).
    review_status   VARCHAR(20) NOT NULL DEFAULT 'AI_DRAFT' CHECK (review_status IN ('AI_DRAFT')),
    abstain_reason  VARCHAR(40),
    limitations     JSONB NOT NULL DEFAULT '[]',
    conflicts       JSONB NOT NULL DEFAULT '[]',
    suggested_lead  TEXT,
    -- Reproducibility (TR §6.3 step 10, §15): what was retrieved and what produced the text.
    retrieval       JSONB NOT NULL,
    generator       JSONB NOT NULL,
    validation      JSONB NOT NULL,
    input_tokens    INTEGER,
    output_tokens   INTEGER,
    latency_ms      INTEGER,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (outcome = 'ANSWERED' OR abstain_reason IS NOT NULL)
);
CREATE INDEX idx_answers_case ON answers(case_id, created_at DESC);

CREATE TABLE answer_claims (
    id          UUID PRIMARY KEY,
    case_id     UUID NOT NULL REFERENCES cases(id),
    answer_id   UUID NOT NULL REFERENCES answers(id),
    position    INTEGER NOT NULL,
    text        TEXT NOT NULL,
    -- SOURCE_STATEMENT: someone said it (attributed); DOCUMENTED_FACT: the document itself records it.
    kind        VARCHAR(30) NOT NULL CHECK (kind IN ('SOURCE_STATEMENT', 'DOCUMENTED_FACT')),
    speaker     TEXT,
    UNIQUE (answer_id, position)
);

-- Generic citation table (TR §4.3): target_type/target_id let findings and reports cite segments later.
CREATE TABLE citations (
    id                    UUID PRIMARY KEY,
    case_id               UUID NOT NULL REFERENCES cases(id),
    target_type           VARCHAR(30) NOT NULL CHECK (target_type IN ('ANSWER_CLAIM')),
    target_id             UUID NOT NULL,
    text_segment_id       UUID NOT NULL,
    document_id           UUID NOT NULL REFERENCES documents(id),
    document_title        TEXT NOT NULL,
    page_id               UUID NOT NULL REFERENCES pages(id),
    pdf_page_number       INTEGER NOT NULL,
    printed_page_label    TEXT,
    source_kind           VARCHAR(20) NOT NULL,
    speaker               TEXT,
    quoted_text           TEXT NOT NULL,
    -- Offsets of the quote in the page's source text (embedded or OCR, per source_kind).
    quote_start           INTEGER NOT NULL,
    quote_end             INTEGER NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (quote_end > quote_start)
);
CREATE INDEX idx_citations_target ON citations(target_type, target_id);
CREATE INDEX idx_citations_case ON citations(case_id);

-- Researchers flag citations that don't support the claim (BR Journey B step 6).
CREATE TABLE citation_reports (
    id           UUID PRIMARY KEY,
    case_id      UUID NOT NULL REFERENCES cases(id),
    citation_id  UUID NOT NULL REFERENCES citations(id),
    kind         VARCHAR(30) NOT NULL CHECK (kind IN ('DOES_NOT_SUPPORT_CLAIM', 'QUOTE_MISREPRESENTED', 'WRONG_PAGE', 'OTHER')),
    note         TEXT CHECK (length(note) <= 2000),
    created_by   UUID NOT NULL REFERENCES users(id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_citation_reports_citation ON citation_reports(citation_id);
