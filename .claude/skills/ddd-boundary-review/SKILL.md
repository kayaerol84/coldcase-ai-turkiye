---
name: ddd-boundary-review
description: Review a change in ColdCase AI Türkiye for domain-driven design problems before committing — cross-context coupling, logic in the wrong layer, missing case isolation, unnamed or misnamed domain concepts, invariants enforced only in the UI. Use after implementing a feature or refactor that touches more than one package or adds domain rules.
---

# DDD boundary review

Review the diff (`git diff` / `git diff origin/main...HEAD`) against this checklist. Report findings as
`file:line — problem — suggested fix`, most serious first. Fix what is clearly in scope; record the rest as debt in
`docs/kb/status.md` and `docs/kb/domain/context-map.md`.

## Boundaries
- [ ] New imports between top-level packages are allowed by `docs/kb/domain/context-map.md`.
      Run `./gradlew test --tests '*ContextBoundariesTest*'`. If you add an edge, it must be a conscious decision:
      update the map and the test together, and prefer a port, query interface or event over a direct dependency.
- [ ] No context reads or writes another context's tables directly (SQL in a repository touches only its own tables,
      plus joins to `cases`/`users` for authorisation and display).
- [ ] References across aggregates are by ID; no copying of source text without its segment/page provenance.
- [ ] External systems are reached only through a port; no vendor SDK types in domain or service signatures.

## Domain logic placement
- [ ] Controllers only map HTTP ↔ commands; rules live in services/aggregates.
- [ ] Invariants are enforced server-side (service + DB constraint where possible), never only in the web app.
- [ ] Status changes go through an explicit transition check, not ad-hoc field updates.

## Case isolation and security
- [ ] Every case-scoped operation calls `CaseAccessService.require` with the narrowest `Permission`.
- [ ] Every new table has `case_id`; every query on case data filters by it **in SQL**.
- [ ] New endpoints have fixtures in `AuthorizationMatrixTest`; errors use stable codes.
- [ ] No document text, queries, prompts or tokens in logs, metrics labels, audit details or exception messages.

## Language and provenance
- [ ] Names match `docs/kb/domain/glossary.md`; new terms are added there.
- [ ] Derived data records producer and version (extractor, OCR, chunker, model, prompt).
- [ ] AI output is marked as draft/suggestion; nothing labels retrieval scores or AI output as evidence.
- [ ] Test fixtures use fictional people.

## Finish
Update `docs/kb/domain/invariants.md` for any new or changed rule and `docs/kb/status.md` for new debt.
