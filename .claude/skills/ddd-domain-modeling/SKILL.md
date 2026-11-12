---
name: ddd-domain-modeling
description: Domain-driven design for ColdCase AI Türkiye. Use before designing or implementing a new feature, aggregate, entity, table, API resource or module — e.g. T3 answers and citations, T4 statements, comparisons, findings, research leads, T5 reports — or when unsure which bounded context owns something. Produces a short model (context, aggregates, invariants, language) and updates the knowledge base.
---

# DDD domain modeling (ColdCase AI Türkiye)

Model first, then code. The domain is investigative research over historical sources, where the core risks are
**misattribution, lost provenance and presenting suggestions as facts**. Good models make those impossible to
express, not merely discouraged.

## 1. Load context
Read `docs/kb/domain/glossary.md`, `docs/kb/domain/context-map.md`, `docs/kb/domain/invariants.md`, and the
relevant sections of `docs/business_requirements.md` / `docs/technical_requirements.md`.

## 2. Find the bounded context
- Which context owns the new concept? (Identity & Access, Source Ingestion, Retrieval, Q&A, Investigation, Reporting.)
- If none fits, propose a new context and say why; don't stretch an existing one.
- A concept that means different things in two contexts gets two models (e.g. a *passage* in Retrieval is a
  segment with offsets; in Investigation it is a cited, reviewed quotation).

## 3. Name things in the ubiquitous language
- Use glossary terms in classes, tables, endpoints and UI copy. Add missing terms (English code name + Turkish UI label + meaning).
- Avoid the glossary's "words to avoid" (evidence, confidence about people, contradiction for mere differences).

## 4. Design aggregates
For each aggregate write down:
- **Root and members**, and its identity. Every case-owned aggregate carries `caseId`.
- **Invariants it protects** (rules that must hold after every command). Examples in this domain:
  - A Finding always has ≥1 Citation and a classification label; it can't become `APPROVED` without a reviewer
    different from its author.
  - A Citation references a segment that exists in the **same case** and its quote is a verbatim substring of that
    segment (no paraphrase presented as quotation — TR §7.3).
  - An AI-produced item starts as `AI_DRAFT`/`AI_SUGGESTED` and only a human command changes that.
  - A Statement keeps its qualifiers ("as far as I know", "I heard") and its speaker attribution.
  - A Lead distinguishes the four missing-document states (TR §9).
- **Commands** (what changes it) and **domain events** worth recording (each security/workflow-relevant
  command also writes an audit event).
- Keep aggregates small; reference other aggregates by ID only (e.g. Finding → segment IDs, not segment copies).

## 5. State machines over booleans
Model statuses as explicit enums with allowed transitions (a function `canTransition(from, to, actorRole)`),
tested exhaustively. Review status, lead status and report status all need this.

## 6. Ports for anything external
LLM, OCR, embeddings, storage: define a port in the owning context, adapters elsewhere. Record the provider and
version on every derived record.

## 7. Map it to this codebase
- Package per context (`tr.coldcase.api.<context>`), service methods = commands, repository per aggregate.
- Authorise in the service via `CaseAccessService.require` with the right `Permission` (add one if needed).
- Migration: new `V<n>__<name>.sql`, `case_id` on every table, constraints for invariants that the database can hold.
- Enforce invariants in the aggregate/service **and** with DB constraints where possible; test both.

## 8. Output and KB updates
Write the model as a short section (context, aggregates + invariants, commands/events, statuses, ports) in the
PR/commit description or an ADR if it is architectural. Then update:
`docs/kb/domain/glossary.md`, `docs/kb/domain/context-map.md` (+ `ContextBoundariesTest` if dependencies change),
`docs/kb/domain/invariants.md` (one row per new rule with its enforcing code and test), `docs/kb/architecture.md`.
