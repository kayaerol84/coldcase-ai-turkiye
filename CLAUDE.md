# ColdCase AI Türkiye — agent instructions

Source-grounded research platform for historical case documents (Turkish). It must never present AI output,
retrieval scores or allegations as established fact. See `docs/business_requirements.md` and
`docs/technical_requirements.md`.

## Knowledge base — use it and keep it current
The knowledge base in `docs/kb/` is this project's working memory.
1. **Start of every task:** read `docs/kb/README.md`, `docs/kb/status.md`, and the KB pages for the area you touch.
2. **While working:** use `docs/kb/domain/glossary.md` terms; respect `docs/kb/domain/context-map.md`; check
   `docs/kb/domain/invariants.md` for rules your change could break; follow `docs/kb/conventions.md` and
   `docs/kb/security.md`.
3. **Before you finish:** update every KB page your change made inaccurate (protocol in `docs/kb/README.md`),
   including `status.md` and, if you debugged something surprising, `lessons.md`.
A Stop hook (`.claude/hooks/kb-check.sh`) reminds you if code changed but the KB did not.

## Skills in this repo (`.claude/skills/`)
- `ddd-domain-modeling` — before designing a new feature, aggregate, table or module (T3 Q&A, T4 investigation…).
- `ddd-boundary-review` — before committing changes that cross packages or add domain logic.
- `kb-maintenance` — how to update the knowledge base well.

## Commands
```bash
cd services/api && ./gradlew test            # API tests (needs PostgreSQL with vector + pg_trgm, see docs/kb/conventions.md)
cd services/api && ./gradlew securityTest    # security-tagged tests only
cd apps/web && npm run lint && npm run build
python3 scripts/check-kb.py                  # KB path references are valid
```

## Non-negotiables
- Authorise every case-scoped operation through `CaseAccessService`; non-members get 404.
- Never log document text, queries, prompts or tokens. Never send case content to external services without an ADR.
- Originals are immutable; derived data records its producer's version.
- Test fixtures use fictional people.
