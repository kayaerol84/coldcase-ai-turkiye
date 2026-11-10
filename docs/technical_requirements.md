# ColdCase AI Türkiye --- Technical Requirements Document

**Document:** `technical_requirements.md`\
**Version:** 0.1\
**Status:** Draft for architecture and implementation planning\
**Related document:** `business_requirements.md`

------------------------------------------------------------------------

## 1. Purpose

This document defines the proposed technical architecture and
implementation requirements for ColdCase AI Türkiye's MVP.

The platform will ingest case documents, preserve original-file
provenance, extract and index page-level content, answer questions using
retrieval-grounded AI, compare source statements, manage research leads,
and generate traceable reports.

The system must treat AI output as assistance, not authoritative
evidence. All material findings must remain linked to source records and
review status.

## 2. Architecture Overview

### 2.1 Logical architecture

``` mermaid
flowchart TD
    U[Researcher / Reviewer] --> WEB[Web Application]
    WEB --> API[Backend API]
    API --> AUTH[Authentication & Authorization]
    API --> CASE[Case and Document Service]
    API --> SEARCH[Search and Retrieval Service]
    API --> FIND[Findings and Investigation Service]
    API --> REPORT[Report Service]

    CASE --> OBJ[(Object Storage)]
    CASE --> DB[(PostgreSQL)]
    CASE --> QUEUE[Job Queue]
    QUEUE --> WORKER[Document Processing Workers]
    WORKER --> OCR[PDF Text Extraction / OCR]
    WORKER --> CHUNK[Page Segmentation and Chunking]
    WORKER --> EMB[Embedding Generation]
    WORKER --> DB
    WORKER --> VEC[(Vector Index)]

    SEARCH --> DB
    SEARCH --> VEC
    SEARCH --> LLM[LLM Provider Adapter]
    LLM --> VALIDATE[Citation and Output Validator]
    VALIDATE --> API

    FIND --> DB
    REPORT --> DB
    REPORT --> OBJ
    API --> AUDIT[Audit Log]
```

### 2.2 Proposed technology stack

  -----------------------------------------------------------------------
  Layer                   Initial recommendation  Notes
  ----------------------- ----------------------- -----------------------
  Frontend                Next.js + TypeScript    Responsive research
                                                  workspace

  UI                      React component         Dark forensic theme for
                          system + accessible     demo; support
                          design tokens           accessible contrast

  Backend                 Kotlin + Spring Boot    Modular monolith for
                                                  MVP

  API                     REST + OpenAPI          Versioned endpoints

  Database                PostgreSQL              Transactional case and
                                                  research data

  Vector search           pgvector                Keep initial operations
                                                  simple

  Full-text search        PostgreSQL full-text    Add dedicated search
                          search                  engine only if needed

  Object storage          S3-compatible storage   Original files and
                                                  generated exports

  Async processing        Queue abstraction;      Document jobs should
                          managed queue or        not block API requests
                          RabbitMQ                

  PDF extraction          Apache Tika / PDFBox    Extract text and page
                                                  boundaries

  OCR                     OCRmyPDF + Tesseract    Evaluate Turkish OCR
                          initially               quality; support
                                                  page-level retries

  Embeddings              Provider adapter        Model choice subject to
                                                  Turkish-language
                                                  evaluation

  LLM                     Provider adapter        Do not couple domain
                                                  logic to one vendor

  Authentication          OIDC-compatible         MFA for privileged
                          identity provider       roles

  Observability           OpenTelemetry +         Metrics, traces,
                          Prometheus/Grafana      structured logs

  Deployment              Docker; cloud           Final provider and
                          deployment in EU region topology are open
                                                  decisions

  CI/CD                   GitHub Actions or       Tests, scans, build,
                          GitLab CI               deploy
  -----------------------------------------------------------------------

This is a recommended starting stack, not a constraint. Keep the backend
modular so components can be replaced without changing domain contracts.

## 3. Architectural Principles

1.  **Immutable originals:** Never modify the original uploaded source
    file.
2.  **Page-level provenance:** Every extracted text segment must retain
    its document and page identity.
3.  **Tenant/case isolation:** Every query and database access must
    enforce case-level permissions.
4.  **Retrieval before generation:** The LLM must receive only
    retrieved, permission-filtered source passages for corpus-grounded
    answers.
5.  **Citation validation:** Reject or flag generated citations that do
    not map to retrieved passages.
6.  **Human review:** AI-generated claims, comparisons, and leads are
    suggestions until reviewed.
7.  **Version everything:** Record source file hash, extraction version,
    OCR engine/version, chunking version, embedding model, and LLM
    model/version.
8.  **Provider abstraction:** Model and storage providers must be
    replaceable.
9.  **Fail safely:** When evidence is missing or retrieval is weak,
    abstain rather than invent an answer.
10. **Security by default:** Private cases, least privilege, encryption,
    auditability, and retention controls.

## 4. Core Domain Model

### 4.1 Main entities

  -----------------------------------------------------------------------
  Entity                              Purpose
  ----------------------------------- -----------------------------------
  User                                Authenticated platform user

  Organisation                        Optional tenant or research group

  Case                                Investigation workspace

  CaseMembership                      User role and access to a case

  Document                            Logical document record and
                                      metadata

  DocumentVersion                     Immutable uploaded file version and
                                      hash

  Page                                Page-level extraction and
                                      processing metadata

  TextSegment                         Searchable passage tied to a page
                                      and character offsets

  Embedding                           Vector representation of a text
                                      segment

  Entity                              Person, organisation, location, or
                                      other named entity

  EntityMention                       Mention of an entity in a source
                                      segment

  Event                               Date/time and description of a
                                      source-linked event

  Statement                           Attributed statement or claim
                                      extracted from a source

  StatementComparison                 Researcher-reviewed relationship
                                      between statements

  Finding                             Research conclusion or observation
                                      with source references

  ResearchLead                        Follow-up question or
                                      missing-document task

  Citation                            Link from an answer/finding/report
                                      to a source segment

  Annotation                          Researcher note attached to a
                                      source or finding

  AuditEvent                          Security- and workflow-relevant
                                      action record

  ProcessingJob                       Extraction, OCR, indexing, or
                                      export job
  -----------------------------------------------------------------------

### 4.2 Data modelling rules

-   All case-owned records must include `case_id`.
-   All source-derived records must retain `document_id` and `page_id`
    where applicable.
-   Store both the PDF page index (zero- or one-based convention must be
    consistent) and printed page label when available.
-   Do not assume printed page labels match PDF page numbers.
-   Entity resolution must preserve the original mention and its source
    context.
-   Do not merge two people based on name similarity alone.
-   A relationship edge must have provenance and a relationship type;
    association is not proof of coordination.
-   Deletion must respect legal holds, retention policies, and audit
    requirements.

### 4.3 Suggested core schema

``` sql
CREATE TABLE cases (
    id UUID PRIMARY KEY,
    title TEXT NOT NULL,
    description TEXT,
    primary_language VARCHAR(10) NOT NULL DEFAULT 'tr',
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE documents (
    id UUID PRIMARY KEY,
    case_id UUID NOT NULL REFERENCES cases(id),
    title TEXT NOT NULL,
    source_name TEXT,
    source_date DATE,
    language VARCHAR(10),
    status VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE document_versions (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES documents(id),
    object_key TEXT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    mime_type TEXT NOT NULL,
    file_size_bytes BIGINT NOT NULL,
    uploaded_by UUID NOT NULL,
    uploaded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(document_id, sha256)
);

CREATE TABLE pages (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES documents(id),
    document_version_id UUID NOT NULL REFERENCES document_versions(id),
    pdf_page_number INTEGER NOT NULL,
    printed_page_label TEXT,
    extraction_status VARCHAR(30) NOT NULL,
    extraction_method VARCHAR(30),
    extraction_quality NUMERIC(5,4),
    extracted_text TEXT,
    UNIQUE(document_version_id, pdf_page_number)
);

CREATE TABLE text_segments (
    id UUID PRIMARY KEY,
    case_id UUID NOT NULL REFERENCES cases(id),
    document_id UUID NOT NULL REFERENCES documents(id),
    page_id UUID NOT NULL REFERENCES pages(id),
    segment_index INTEGER NOT NULL,
    text TEXT NOT NULL,
    char_start INTEGER,
    char_end INTEGER,
    metadata JSONB NOT NULL DEFAULT '{}'
);

CREATE TABLE findings (
    id UUID PRIMARY KEY,
    case_id UUID NOT NULL REFERENCES cases(id),
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    status VARCHAR(40) NOT NULL,
    created_by UUID NOT NULL,
    reviewed_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_at TIMESTAMPTZ
);

CREATE TABLE citations (
    id UUID PRIMARY KEY,
    case_id UUID NOT NULL REFERENCES cases(id),
    text_segment_id UUID NOT NULL REFERENCES text_segments(id),
    target_type VARCHAR(30) NOT NULL,
    target_id UUID NOT NULL,
    quoted_text TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE research_leads (
    id UUID PRIMARY KEY,
    case_id UUID NOT NULL REFERENCES cases(id),
    title TEXT NOT NULL,
    description TEXT,
    status VARCHAR(30) NOT NULL,
    priority VARCHAR(20),
    owner_id UUID,
    origin_finding_id UUID REFERENCES findings(id),
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

The schema is illustrative. Production migrations must include indexes,
constraints, membership checks, timestamps, retention metadata, and
audit relationships.

## 5. Document Ingestion Pipeline

### 5.1 Workflow

1.  Client requests an upload session.
2.  Backend validates user permission and case access.
3.  File is uploaded to private object storage.
4.  Backend calculates SHA-256 and records file metadata.
5.  File type and size are validated using content inspection, not
    filename alone.
6.  A processing job is queued.
7.  Worker extracts embedded PDF text page by page.
8.  Pages with insufficient or absent text are flagged for OCR.
9.  OCR output is stored separately from original embedded text.
10. Page metadata and printed page labels are captured where possible.
11. Text is segmented into retrieval chunks without losing page
    provenance.
12. Embeddings are generated and stored.
13. Full-text indexes are updated.
14. Processing status and quality metrics are recorded.
15. The document becomes searchable only when required processing stages
    complete.

### 5.2 Ingestion requirements

-   Support PDF in MVP.
-   Preserve original file bytes and calculate SHA-256.
-   Detect encrypted, corrupt, oversized, and unsupported files.
-   Support retryable asynchronous jobs.
-   Record per-page extraction status.
-   Keep OCR text distinct from embedded PDF text.
-   Record OCR engine and version.
-   Do not silently discard pages with no extractable text.
-   Support manual page review and reprocessing.
-   Prevent untrusted document text from being interpreted as system
    instructions.

### 5.3 Chunking

Recommended initial strategy:

-   Chunk within page boundaries by default.
-   Use paragraph-aware segmentation.
-   Target approximately 400--800 tokens per chunk, with configurable
    overlap.
-   Avoid combining unrelated speakers or transcript sections.
-   Store page number, printed page label, section heading, speaker, and
    offsets as metadata when available.
-   For a chunk spanning pages, retain all contributing page references
    explicitly.

Chunking must be evaluated on Turkish hearing transcripts, tables,
footnotes, and multi-column PDFs.

## 6. Search and Retrieval

### 6.1 Search modes

-   Exact phrase search.
-   Keyword/full-text search.
-   Semantic search.
-   Hybrid retrieval combining lexical and vector results.
-   Filters: case, document, date, speaker/entity, page range, language,
    and processing status.

### 6.2 Retrieval requirements

-   Enforce case permissions before retrieval.
-   Return source segment IDs and page metadata with every result.
-   Prefer exact names, dates, and quoted phrases when the user supplies
    them.
-   Deduplicate overlapping chunks.
-   Preserve multiple relevant accounts when disagreement is likely.
-   Expose retrieval scores for internal diagnostics, not as proof of
    truth.
-   Allow users to inspect the original page or a faithful page
    rendering.

### 6.3 RAG answer generation

The answer pipeline:

1.  Parse the question and identify case scope.
2.  Retrieve candidate passages from the permitted corpus.
3.  Rerank candidates.
4.  Build a context package containing source text and metadata.
5.  Generate an answer under a strict evidence-grounding prompt.
6.  Extract claim-to-citation mappings.
7.  Validate each citation against retrieved segment IDs.
8.  Check whether each material factual claim has supporting citations.
9.  Return answer, citations, limitations, and relevant conflicting
    passages.
10. Log model and retrieval metadata for reproducibility.

**Abstention behaviour:** If the corpus does not contain adequate
support, the assistant must say that the available documents do not
establish the answer. It may suggest what evidence would be needed,
clearly labelled as a research lead.

## 7. Statement and Claim Analysis

### 7.1 Statement extraction

Each extracted statement should contain:

-   Speaker or attributed source.
-   Exact source passage.
-   Document and page reference.
-   Hearing/interview date, if available.
-   Topic or event.
-   Extracted proposition.
-   Qualifiers and uncertainty language.
-   Extraction method and model version.
-   Review status.

### 7.2 Comparison categories

Use explicit categories:

-   Supports / corroborates.
-   Contradicts (only where propositions are materially incompatible).
-   Different scope or level of detail.
-   Chronology difference.
-   Possible inconsistency requiring review.
-   No meaningful comparison.
-   Insufficient context.

AI classifications must remain marked as suggestions until a researcher
approves them.

### 7.3 Guardrails

-   Do not infer deception from inconsistency.
-   Do not assign personal credibility or guilt scores.
-   Do not treat absence from one document as proof an event did not
    occur.
-   Preserve qualifiers such as "as far as I know", "I heard", "I
    assume", and "I do not remember".
-   Do not turn paraphrase into a direct quotation.
-   Display surrounding context when a quote could be misleading.

## 8. Entity, Event, and Relationship Extraction

### 8.1 Entity types

-   Person
-   Organisation
-   Institution
-   Location
-   Vehicle or object
-   Document
-   Other configurable type

### 8.2 Event types

-   Hearing or interview
-   Phone call or communication
-   Document request or response
-   Evidence collection or transfer
-   Reported sighting
-   Alleged threat
-   Forensic examination
-   Other source-derived event

### 8.3 Provenance requirements

Every entity mention, event, and relationship must link to one or more
source segments. The UI must distinguish:

-   Directly stated relationship.
-   AI-inferred candidate relationship.
-   Researcher-confirmed relationship.

A graph edge must never be presented as evidence of wrongdoing merely
because two entities are connected in the graph.

## 9. Research Lead Management

A research lead must support:

-   Title and description.
-   Originating source passage or finding.
-   Lead type: missing document, unverified claim, follow-up interview,
    date discrepancy, forensic question, or other.
-   Status: open, in progress, blocked, resolved, dismissed.
-   Priority and owner.
-   Next action and due date.
-   Related entities, events, documents, and findings.
-   Resolution notes and supporting sources.

The system must distinguish: 1. Document explicitly requested and not
received. 2. Document referred to but not present in the current corpus.
3. Document hypothesised to exist but not yet evidenced. 4. Document
found and reviewed.

## 10. API Requirements

Use versioned REST endpoints under `/api/v1`.

### 10.1 Cases

``` http
POST   /api/v1/cases
GET    /api/v1/cases
GET    /api/v1/cases/{caseId}
PATCH  /api/v1/cases/{caseId}
```

### 10.2 Documents

``` http
POST   /api/v1/cases/{caseId}/documents/upload-session
POST   /api/v1/cases/{caseId}/documents
GET    /api/v1/cases/{caseId}/documents
GET    /api/v1/documents/{documentId}
GET    /api/v1/documents/{documentId}/pages/{pageNumber}
POST   /api/v1/documents/{documentId}/reprocess
```

### 10.3 Search and Q&A

``` http
POST   /api/v1/cases/{caseId}/search
POST   /api/v1/cases/{caseId}/questions
GET    /api/v1/answers/{answerId}
```

Example question request:

``` json
{
  "question": "Telefon kayıtlarının elde edilememesi hakkında tutanaklarda ne söyleniyor?",
  "filters": {
    "documentIds": [],
    "printedPageFrom": null,
    "printedPageTo": null
  }
}
```

Example response shape:

``` json
{
  "answerId": "uuid",
  "answer": "Retrieved-source-grounded answer...",
  "limitations": [
    "The original correspondence was not verified in the indexed corpus."
  ],
  "citations": [
    {
      "documentId": "uuid",
      "pageId": "uuid",
      "pdfPageNumber": 407,
      "printedPageLabel": "1427",
      "textSegmentId": "uuid",
      "quote": "Source excerpt..."
    }
  ],
  "reviewStatus": "AI_DRAFT"
}
```

### 10.4 Findings and statements

``` http
POST   /api/v1/cases/{caseId}/findings
GET    /api/v1/cases/{caseId}/findings
PATCH  /api/v1/findings/{findingId}
POST   /api/v1/findings/{findingId}/review
POST   /api/v1/cases/{caseId}/statements/extract
POST   /api/v1/cases/{caseId}/statement-comparisons
GET    /api/v1/cases/{caseId}/statement-comparisons
```

### 10.5 Research leads and reports

``` http
POST   /api/v1/cases/{caseId}/leads
GET    /api/v1/cases/{caseId}/leads
PATCH  /api/v1/leads/{leadId}
POST   /api/v1/cases/{caseId}/reports
GET    /api/v1/reports/{reportId}
```

All endpoints must enforce authentication, case membership, role
permissions, input validation, and audit logging where relevant. Exact
request/response schemas should be formalised in OpenAPI.

## 11. Frontend Requirements

### 11.1 Main screens

1.  Case dashboard.
2.  Case document library.
3.  Document viewer with page navigation and source highlighting.
4.  AI research assistant.
5.  Evidence and findings register.
6.  Statement comparison workspace.
7.  Timeline.
8.  Entity and relationship explorer.
9.  Research leads board.
10. Report builder and export.
11. Case settings and access management.

### 11.2 Document viewer

-   Show PDF page and printed page label separately.
-   Highlight the cited passage.
-   Provide previous/next page navigation.
-   Show extraction method and OCR quality warnings.
-   Allow users to report extraction or citation errors.
-   Never display generated paraphrase as if it were original source
    text.

### 11.3 Visual design

-   Dark forensic visual language for the demo: charcoal/navy
    backgrounds, restrained cyan accents, neutral surfaces, and
    amber/red only for meaningful warnings.
-   Avoid sensational imagery, crime-scene spectacle, or visual cues
    implying guilt.
-   Keep source text readable and provide accessible contrast.
-   Status labels must include text, not colour alone.
-   Responsive desktop-first layout for research workflows.

## 12. Security, Privacy, and Compliance

### 12.1 Authentication and authorisation

-   OIDC/OAuth2 authentication.
-   MFA for administrators and reviewers.
-   Role-based access control with case-level membership.
-   Server-side authorisation on every request.
-   Short-lived access tokens and secure refresh-token handling.
-   No client-side-only permission checks.

### 12.2 Data protection

-   TLS in transit.
-   Encryption at rest for database, object storage, and backups.
-   Secrets stored in a managed secret store.
-   Data minimisation for personal data.
-   Redaction support for exports where required.
-   Configurable retention and deletion policies.
-   Data residency decision documented before production.
-   Data processing agreements and provider retention settings reviewed
    before using external AI services.

### 12.3 Audit

Log at minimum:

-   Login and access events.
-   Case membership and role changes.
-   Document upload, download, deletion, and reprocessing.
-   Source viewing where required by policy.
-   Finding creation and edits.
-   Review/approval actions.
-   Report generation and export.
-   Administrative configuration changes.

Audit logs must be append-only from the application perspective and
protected from ordinary user modification.

### 12.4 Prompt injection and untrusted documents

Uploaded documents are untrusted data. Their content must never override
system or developer instructions. Retrieval passages must be clearly
delimited as source material. The model must not follow instructions
found inside source documents.

## 13. Reliability and Performance

Initial targets for a small pilot; validate with load testing:

  -----------------------------------------------------------------------
  Area                                Target
  ----------------------------------- -----------------------------------
  API availability                    99.5% monthly during pilot

  Standard API latency                p95 under 500 ms, excluding AI and
                                      file processing

  Search latency                      p95 under 3 seconds for a pilot
                                      corpus

  AI answer latency                   p95 under 20 seconds for standard
                                      questions

  Upload                              Asynchronous processing; UI remains
                                      responsive

  Job retry                           Retry transient processing failures
                                      with bounded backoff

  Backup                              Daily database backups;
                                      object-storage durability policy

  Recovery                            Define and test RPO/RTO before
                                      production
  -----------------------------------------------------------------------

Large documents and OCR-heavy files must be processed asynchronously. UI
requests must not wait for full-document processing.

## 14. Observability and Operations

Capture:

-   Upload and processing success/failure rates.
-   Pages extracted and OCR pages.
-   OCR quality distribution.
-   Queue depth and job duration.
-   Search latency and empty-result rate.
-   Retrieval relevance feedback.
-   Citation validation failure rate.
-   LLM latency, token usage, and provider errors.
-   User feedback on answers and citations.
-   Authentication and authorisation failures.

Do not log full sensitive document text or prompts by default. Apply
redaction and retention controls to operational logs.

## 15. AI Evaluation and Quality Gates

Create a curated benchmark from the initial case corpus. Each benchmark
item should contain:

-   User question.
-   Expected source document and page(s).
-   Relevant source passage.
-   Expected answer points.
-   Known limitations or ambiguity.
-   Acceptable abstention behaviour.

Evaluate:

1.  Retrieval recall at k.
2.  Citation precision and correctness.
3.  Claim-level support.
4.  Faithfulness to source wording and qualifiers.
5.  Correct handling of contradictory accounts.
6.  Abstention when support is missing.
7.  Turkish names, dates, institutions, and OCR robustness.
8.  Resistance to prompt injection in uploaded material.

No model or prompt change should be released without running the
benchmark. Keep model, prompt, embedding, and retrieval configuration
versions with each answer.

## 16. Testing Strategy

### Unit tests

-   Domain validation and state transitions.
-   Citation-to-source mapping.
-   Case permission checks.
-   Hash and duplicate handling.
-   Finding and lead status transitions.

### Integration tests

-   PDF upload to indexed page.
-   OCR fallback and retry.
-   Search and retrieval.
-   RAG answer and citation validation.
-   Report generation.
-   Audit event creation.

### End-to-end tests

-   Create case, upload PDF, ask question, open citation.
-   Compare two statements and save review.
-   Create a missing-document lead and export register.
-   Verify a user cannot access an unauthorised case.

### Security tests

-   IDOR and cross-case access.
-   File upload validation and malware scanning integration.
-   Prompt injection through document content.
-   Secret leakage and log exposure.
-   Rate limiting and abuse controls.

## 17. Deployment and Environments

Maintain separate environments:

-   Local development.
-   Shared development.
-   Staging / acceptance.
-   Production.

Requirements:

-   Infrastructure as code.
-   Automated database migrations.
-   Container image scanning.
-   Dependency and secret scanning.
-   Automated unit/integration tests in CI.
-   Manual approval for production release.
-   Backward-compatible API and database changes where practical.
-   Rollback procedure for application releases.
-   Document-processing workers scaled independently from API services.

## 18. Suggested Repository Structure

``` text
coldcase-ai-turkiye/
├── apps/
│   └── web/                       # Next.js frontend
├── services/
│   └── api/                       # Kotlin Spring Boot modular monolith
├── workers/
│   └── document-processor/        # PDF extraction, OCR, indexing
├── packages/
│   ├── api-contracts/
│   └── shared-types/
├── infra/
│   ├── docker/
│   └── terraform/
├── docs/
│   ├── business_requirements.md
│   └── technical_requirements.md
├── evals/
│   ├── questions/
│   └── expected-citations/
└── .github/
    └── workflows/
```

## 19. Implementation Milestones

  -----------------------------------------------------------------------
  Milestone               Technical output        Exit criteria
  ----------------------- ----------------------- -----------------------
  T0 --- Foundation       Repo, CI, environments, Secure case creation
                          auth, case model        and access

  T1 --- Ingestion        Upload, hash, storage,  Searchable page-level
                          PDF extraction, page    corpus
                          records                 

  T2 --- Retrieval        Full-text/vector search Relevant passages
                          and source viewer       returned with correct
                                                  page

  T3 --- RAG              Q&A, citation mapping,  Benchmark citation
                          validation, abstention  targets met

  T4 --- Investigation    Statements,             Human-reviewed
                          comparisons, findings,  workflows work
                          leads                   end-to-end

  T5 --- Reporting        Report generation and   Export is
                          audit                   source-traceable

  T6 --- Pilot            Security, performance,  Pilot acceptance signed
                          UX, evaluation          off
  -----------------------------------------------------------------------

## 20. Open Technical Decisions

1.  Cloud provider and EU region.
2.  Authentication provider and organisation/tenant model.
3.  Object-storage provider and retention policy.
4.  Queue technology.
5.  OCR engine and Turkish-language quality benchmark.
6.  Embedding and LLM providers, data retention, and processing
    location.
7.  Whether a graph database is justified after MVP.
8.  Maximum PDF size, pages per document, and total corpus size.
9.  Whether reports require PDF, Markdown, DOCX, or multiple formats.
10. Data deletion, legal hold, and backup expiry requirements.

## 21. Definition of Done

A feature is complete only when:

-   Acceptance criteria are met.
-   Unit, integration, and relevant end-to-end tests pass.
-   Access control and audit requirements are implemented.
-   Source provenance is preserved.
-   Errors and limitations are visible to users.
-   Telemetry is added without exposing sensitive content.
-   Documentation and API contracts are updated.
-   AI features pass the relevant evaluation benchmark.
-   Human review requirements are enforced in the UI and API.

------------------------------------------------------------------------

**Document owner:** Engineering\
**Review status:** Draft --- architecture, security, and product
decisions remain open
