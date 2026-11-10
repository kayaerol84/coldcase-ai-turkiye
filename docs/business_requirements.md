# ColdCase AI Türkiye --- Business Requirements Document

**Document:** `business_requirements.md`\
**Version:** 0.1\
**Status:** Draft for product discovery and MVP planning\
**Product:** ColdCase AI Türkiye\
**Initial demonstration case:** Uğur Mumcu assassination --- TBMM
Parliamentary Research Commission, Volume 2

------------------------------------------------------------------------

## 1. Executive Summary

ColdCase AI Türkiye is a source-grounded investigative research
platform. It helps researchers examine large collections of historical
case documents, retrieve relevant passages, compare witness and official
accounts, identify unresolved questions, and maintain traceable research
records.

The product is **not** an automated crime-solving system and must not
declare guilt, identify perpetrators, or present unverified allegations
as facts. Its primary value is to make complex documentary
investigations more searchable, structured, transparent, and auditable.

The first MVP will use the TBMM Parliamentary Research Commission
document concerning the Uğur Mumcu assassination as a demonstration
corpus. The initial demo work has identified research themes in Volume
2, including telephone records, disks and home inspection, crime-scene
evidence, witness accounts, alleged threats, and explosive analysis.
These are leads for documentary analysis, not conclusions about
responsibility for the assassination.

## 2. Product Vision

**Vision:** Give investigators and researchers a reliable workspace to
move from thousands of pages of fragmented records to a transparent,
evidence-linked understanding of what is documented, disputed, missing,
and still needs verification.

**Product promise:** Every material answer should be traceable to its
source, and uncertainty should be visible rather than hidden.

### 2.1 Product principles

1.  **Evidence first:** Answers must link to source documents and
    precise page or passage references.
2.  **No unsupported conclusions:** The system distinguishes source
    statements, corroborated facts, disputed accounts, unverified
    allegations, and analytical hypotheses.
3.  **Human-led investigation:** AI assists research; a human researcher
    reviews and approves material findings.
4.  **Preserve provenance:** Original files, extracted text, page
    references, and processing history remain linked.
5.  **Make uncertainty explicit:** Missing, contradictory, ambiguous,
    and technically inconclusive information must be labelled.
6.  **Neutrality:** The system must not favour a political,
    institutional, personal, or ideological narrative.
7.  **Privacy and safety:** Sensitive personal information and
    allegations require careful access controls and restrained
    presentation.

## 3. Problem Statement

Historical and complex investigations often involve large volumes of
PDFs, hearing transcripts, reports, correspondence, media records, and
witness statements. Researchers may need to:

-   Search hundreds or thousands of pages for a person, event, date, or
    subject.
-   Find different accounts of the same event.
-   Understand whether two accounts genuinely conflict or merely address
    different aspects.
-   Track documents that are referenced but absent from the available
    collection.
-   Build timelines and relationship maps without confusing association
    with proof.
-   Produce reports that other researchers can independently verify.

Traditional document search and general-purpose chat interfaces do not
consistently provide the source traceability, uncertainty handling, and
structured investigative workflow required for this work.

## 4. Goals and Success Measures

### 4.1 MVP goals

-   Ingest and index a defined collection of PDF case documents.
-   Allow natural-language questions over the indexed corpus.
-   Return answers with citations to the exact document and page.
-   Extract candidate people, organisations, dates, places, events, and
    evidence references.
-   Compare selected statements on a shared topic.
-   Create an actionable list of missing or follow-up materials.
-   Allow researchers to review, correct, annotate, and export findings.

### 4.2 Success metrics

  ------------------------------------------------------------------------
  Metric                              Initial target Measurement
  --------------------- ---------------------------- ---------------------
  Answer citation             100% of factual answer Automated checks and
  coverage                                paragraphs review

  Citation correctness   ≥ 95% on curated evaluation Human-verified
                                                 set benchmark

  Unsupported factual         0 critical unsupported Red-team and QA
  claims                         claims in benchmark review

  Page retrieval          ≥ 95% on curated questions Expected-page
  accuracy                                           comparison

  Document ingestion            ≥ 98% for supported, Ingestion logs
  success                         text-readable PDFs 

  Researcher task           Complete core demo tasks Usability test
  completion                     without engineering 
                                          assistance 

  Reviewability              Every saved finding has Database validation
                          source and status metadata 
  ------------------------------------------------------------------------

Targets are provisional and must be calibrated using a representative
test corpus. OCR quality and source-document quality may affect results.

## 5. Users and Roles

### 5.1 Primary users

-   **Historical researcher:** Searches records, compares accounts,
    builds timelines, and prepares reports.
-   **Investigative journalist:** Verifies claims and follows
    documentary leads.
-   **Research analyst:** Structures entities, events, and evidence
    references.
-   **Case administrator:** Manages case workspaces, documents, users,
    and permissions.

### 5.2 Roles and permissions

  -----------------------------------------------------------------------
  Role                                Permissions
  ----------------------------------- -----------------------------------
  Viewer                              View approved case material,
                                      sources, and published findings

  Researcher                          Search documents, create findings,
                                      compare statements, add notes and
                                      leads

  Reviewer                            Review, approve, reject, or request
                                      changes to findings

  Case Admin                          Manage case settings, documents,
                                      users, and access

  Platform Admin                      Manage platform-wide configuration
                                      and operational controls
  -----------------------------------------------------------------------

A user's ability to view a case must not automatically grant access to
every other case.

## 6. Scope

### 6.1 MVP --- In scope

1.  Case workspace creation and management.
2.  PDF upload and document metadata capture.
3.  Text extraction, page segmentation, indexing, and processing status.
4.  Full-text and semantic document search.
5.  Source-grounded question answering (RAG).
6.  Page-level citations and source passage preview.
7.  Candidate entity extraction (people, organisations, locations,
    dates).
8.  Statement/claim records linked to source passages.
9.  Side-by-side comparison of accounts on a topic.
10. Finding status and confidence/verification labels.
11. Research leads and missing-document register.
12. Manual researcher annotations and review workflow.
13. Export of a case report in Markdown or PDF.
14. Audit log for important user and document actions.

### 6.2 Later releases

-   Cross-volume and cross-case entity resolution.
-   Interactive relationship graph with provenance on every edge.
-   Timeline visualisation with source-linked events.
-   Advanced OCR for scanned and handwritten documents.
-   Audio/video transcription and analysis.
-   Multilingual document processing and translation.
-   Collaborative review and assignment workflows.
-   External archive integrations and controlled web research.
-   Advanced chain-of-custody and digital evidence management.

### 6.3 Out of scope for MVP

-   Determining who committed a crime.
-   Predicting guilt, criminal propensity, or credibility of a person.
-   Autonomous law-enforcement actions or recommendations.
-   Facial recognition or biometric identification.
-   Covert surveillance, tracking, or personal-data enrichment.
-   Treating AI-generated relationship graphs as proof of coordination.
-   Automatic publication of allegations or personal information.
-   Replacing legal, forensic, or investigative professional judgement.

## 7. Core User Journeys

### Journey A --- Create a case and ingest documents

1.  A Case Admin creates a case workspace.
2.  The admin enters the case title, description, date range, language,
    and access policy.
3.  The admin uploads a PDF.
4.  The system validates the file, calculates a cryptographic hash,
    stores the original, and starts processing.
5.  The system extracts text page by page and records extraction
    quality.
6.  The document becomes searchable when processing completes.
7.  The user can inspect failed pages and retry supported processing
    steps.

**Acceptance criteria** - Original file remains immutable after
ingestion. - Every extracted passage is linked to a document and page. -
The UI shows processing status and errors. - Duplicate uploads are
detected by hash and handled according to case policy.

### Journey B --- Ask a question and verify the answer

1.  A researcher opens a case and asks a natural-language question.
2.  The system retrieves relevant passages from the case corpus.
3.  The AI drafts an answer using retrieved material only.
4.  Each material factual statement is linked to a source passage.
5.  The researcher opens a citation to inspect the source page.
6.  The researcher can flag a citation as incorrect or insufficient.

**Acceptance criteria** - The system does not fabricate page
references. - If relevant support is not found, it says so. - The answer
distinguishes what a witness said from what has been independently
established. - Conflicting passages are surfaced when relevant.

### Journey C --- Compare accounts

1.  The researcher selects a topic, claim, or event.
2.  The system proposes relevant statements and source passages.
3.  The researcher selects which statements to compare.
4.  The system displays the accounts side by side, with dates, speakers,
    context, and citations.
5.  The system classifies the relationship as possible contradiction,
    difference in scope, chronology difference, corroboration, or
    unresolved.
6.  The researcher confirms or edits the classification.

**Acceptance criteria** - The system does not label accounts as
contradictory solely because wording differs. - Context and qualifiers
are retained. - The classification is marked as AI-suggested until
reviewed.

### Journey D --- Track missing documents and leads

1.  A researcher creates a lead from a source passage or analysis.
2.  The researcher records what is missing, why it matters, and where it
    may be held.
3.  The lead receives a status, priority, owner, and next action.
4.  The lead can be linked to related documents, people, events, and
    findings.
5.  The researcher exports the open-leads register.

**Acceptance criteria** - The system distinguishes a document explicitly
requested and not received from a document merely absent from the
current corpus. - Every lead records its origin and the person who
created it. - AI suggestions remain unverified until reviewed.

## 8. Initial Demonstration Case

### 8.1 Source

TBMM, *Uğur Mumcu Cinayetinin Aydınlığa Kavuşturulması Amacıyla Kurulan
Meclis Araştırması Komisyonu*, Volume 2.

The currently reviewed PDF is described in the demo workflow as an
870-page volume. The initial analysis has focused on selected transcript
sections, not a verified page-by-page audit of the complete volume or
all related volumes.

### 8.2 Example research topics

  -----------------------------------------------------------------------
  Topic                   What the demo can show  Important qualification
  ----------------------- ----------------------- -----------------------
  Telephone records       Committee discussion    Original request and
                          about records not       response correspondence
                          reaching the            still need verification
                          committee/file and      
                          comments by Mehmet Ağar 

  Disks and home          Different accounts by   Written and oral
  inspection              Yılmaz Ergün and        requests may be
                          Hayrettin Özdemir       different; not
                                                  automatically a direct
                                                  contradiction

  Crime-scene handling    Committee questions and Testimony and
                          prosecutor's            allegations require
                          explanation of crowded  corroboration
                          scene conditions        

  Hitit Hotel lead        Family account of a     An unverified lead, not
                          visitor claiming        an established
                          knowledge of            confession
                          perpetrators            

  Telegram threat         Witness says no         Does not prove no
                          telegram reached them   threat was ever sent
                          and no document was in  
                          the file                

  Explosive analysis      Technical discussion of RDX alone does not
                          RDX detection and       establish C-4 or source
                          limits of identifying   
                          C-4                     
  -----------------------------------------------------------------------

### 8.3 Example source references used in the demo

-   Printed transcript pages 1026--1030: Yılmaz Ergün.
-   Printed transcript pages 1082--1085: Hayrettin Özdemir.
-   Printed transcript pages 1426--1427: telephone-record discussion.
-   Printed transcript pages 1679--1682: prosecutor Ülkü Coşkun.
-   PDF pages 719--723: technical expert discussion of explosive
    analysis.

The exact page mapping must be confirmed against the original PDF during
ingestion and source validation. The demo should display both PDF page
index and printed transcript page where available.

## 9. Information Classification and Finding Status

The product must use distinct labels:

  -----------------------------------------------------------------------
  Label                               Meaning
  ----------------------------------- -----------------------------------
  Source statement                    A person or institution made this
                                      statement in a source

  Documented fact                     The source directly records the
                                      fact, such as a dated hearing

  Corroborated                        Supported by independent, relevant
                                      sources

  Disputed                            Materially different accounts exist

  Unverified allegation               A claim appears in a source but is
                                      not independently established

  Research lead                       A question or path requiring
                                      further investigation

  Missing from corpus                 Not found in the documents
                                      currently ingested

  Formally requested, not received    Evidence of a request and
                                      non-receipt exists in the sources

  Inconclusive                        Available evidence does not support
                                      a reliable determination
  -----------------------------------------------------------------------

Do not use a single numeric confidence score as a substitute for these
labels. If confidence scoring is later introduced, explain what it
measures and keep it separate from legal guilt or personal credibility.

## 10. Reporting Requirements

A generated report should include:

1.  Case and corpus description.
2.  Research question and date of analysis.
3.  Executive summary.
4.  Findings grouped by topic.
5.  Source passages and page references.
6.  Comparison of relevant accounts.
7.  Disputed and unverified claims.
8.  Missing-document register and follow-up actions.
9.  Limitations, extraction quality, and corpus coverage.
10. Reviewer and approval metadata.

Reports must clearly state whether they are AI-generated drafts,
researcher-reviewed, or approved for external distribution.

## 11. Non-Functional Business Requirements

-   **Trust:** Researchers must be able to verify each factual claim.
-   **Transparency:** The UI must expose source, page, processing
    status, and uncertainty.
-   **Usability:** A first-time researcher should complete a basic
    search and citation inspection without training.
-   **Accessibility:** Core workflows should support keyboard navigation
    and readable contrast.
-   **Language:** Turkish is the initial interface and corpus language;
    architecture should allow additional languages.
-   **Portability:** Users should be able to export their research
    records and reports.
-   **Maintainability:** Case data and source records must not be locked
    into one AI provider.

## 12. Risks and Mitigations

  -----------------------------------------------------------------------
  Risk                    Impact                  Mitigation
  ----------------------- ----------------------- -----------------------
  Hallucinated citations  Critical loss of trust  Retrieval-constrained
                                                  generation, citation
                                                  validation, abstention

  OCR errors              Incorrect names, dates, OCR confidence, page
                          or quotes               image review, manual
                                                  correction

  False contradiction     Misleading              Human review and
  detection               investigation           nuanced comparison
                                                  categories

  Allegation presented as Reputational and legal  Mandatory claim status
  fact                    harm                    and source context

  Sensitive personal data Privacy harm            Role-based access,
  exposure                                        minimisation, redaction

  Biased source           Incomplete or skewed    Corpus coverage
  collection              conclusions             disclosure and source
                                                  diversity

  Model/provider changes  Inconsistent outputs    Versioning, evaluation
                                                  suite, provider
                                                  abstraction

  Copyright or access     Unlawful distribution   Rights metadata and
  restrictions                                    export controls
  -----------------------------------------------------------------------

## 13. MVP Release Acceptance

The MVP is ready for a controlled demonstration when:

-   A user can create a case and upload a supported PDF.
-   The document is extracted and indexed page by page.
-   A user can ask a question and receive an answer with working source
    citations.
-   A user can inspect the original page or extracted passage.
-   A user can compare at least two statements.
-   A user can create and track a missing-document lead.
-   A user can export a report.
-   The interface visibly distinguishes source statements, disputed
    claims, and unverified leads.
-   A curated evaluation set passes the agreed citation and retrieval
    thresholds.
-   Known limitations are disclosed in the demo.

## 14. Open Product Decisions

These decisions must be resolved before implementation is committed:

1.  Is the first release single-user, or does it require multi-user
    collaboration?
2.  Will the initial deployment be cloud-hosted in the EU, self-hosted,
    or both?
3.  Which user groups are the first paying or pilot customers?
4.  What is the policy for uploading copyrighted, confidential, or
    personal-data-heavy records?
5.  Will public-facing reports require mandatory reviewer approval?
6.  Which languages are required in the first release beyond Turkish?
7.  What maximum file size, corpus size, and concurrent-user load should
    be supported?

## 15. Proposed Delivery Plan

  -----------------------------------------------------------------------
  Phase                               Deliverable
  ----------------------------------- -----------------------------------
  0 --- Product validation            User interviews, sample corpus
                                      rights review, curated evaluation
                                      questions

  1 --- Document foundation           Case workspace, upload, immutable
                                      originals, extraction, page-level
                                      index

  2 --- Research assistant            Search, RAG Q&A, source citations,
                                      source preview

  3 --- Investigation workflow        Claims, statement comparison,
                                      finding statuses, research leads

  4 --- Reporting and review          Report export, reviewer workflow,
                                      audit history

  5 --- Pilot hardening               Security review, evaluation,
                                      monitoring, user feedback
  -----------------------------------------------------------------------

------------------------------------------------------------------------

**Document owner:** Product\
**Review status:** Draft --- requires stakeholder validation\
**Related document:** `technical_requirements.md`
