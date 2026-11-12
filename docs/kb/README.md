# ColdCase AI Türkiye — Knowledge Base

The working memory of this codebase for humans and coding agents. **Read the relevant pages
before changing code, and update them in the same change.** A change that alters behaviour,
boundaries, conventions or status without updating the KB is incomplete.

## Pages

| Page | Read it when… |
|---|---|
| [status.md](status.md) | Starting any task: milestone status, open items, known debt |
| [architecture.md](architecture.md) | Touching more than one module, the job pipeline, storage or search |
| [domain/glossary.md](domain/glossary.md) | Naming anything: ubiquitous language (Turkish ↔ English ↔ code) |
| [domain/context-map.md](domain/context-map.md) | Adding a package, a cross-module import, or a new aggregate |
| [domain/invariants.md](domain/invariants.md) | Changing behaviour: the rules from the requirements and where each is enforced and tested |
| [security.md](security.md) | Anything touching auth, case data, uploads, logs, external calls, or the pipeline |
| [conventions.md](conventions.md) | Writing code, tests, migrations, error codes, UI text |
| [lessons.md](lessons.md) | Something behaves oddly: known gotchas and their fixes |
| [decisions.md](decisions.md) | Before re-deciding something: ADR index and smaller decisions |

Sources of truth that the KB summarises but does not replace:
`docs/business_requirements.md`, `docs/technical_requirements.md`, `docs/PLAN.md`, `docs/adr/`.

## Protocol for agents (and humans)

1. **Before work:** read `status.md` and the pages for the area you are touching.
2. **While working:** use the glossary's terms in code, API and UI; respect the context map;
   check the invariants you might affect.
3. **Before finishing:** update every page your change made inaccurate. Typical updates:
   - new/changed endpoint, table, job type or module → `architecture.md`
   - new term → `domain/glossary.md`; new cross-context dependency → `domain/context-map.md`
     **and** `ContextBoundariesTest`
   - new rule enforced or tested → `domain/invariants.md`; security control → `security.md`
   - something surprising you had to debug → `lessons.md`
   - milestone progress, new debt, new open question → `status.md`
4. Keep entries short and factual, with file paths in backticks so they can be checked.
   Remove what is no longer true rather than appending contradictions.

`scripts/check-kb.py` (run in CI) fails if a backticked repository path in the KB doesn't exist.
