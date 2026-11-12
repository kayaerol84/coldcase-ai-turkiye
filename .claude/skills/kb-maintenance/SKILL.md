---
name: kb-maintenance
description: Update the ColdCase AI Türkiye knowledge base in docs/kb after a code change, or audit it for stale content. Use at the end of every task that changed behaviour, structure, endpoints, tables, dependencies, security controls, conventions or project status, and when the Stop hook reports that code changed without a KB update.
---

# Knowledge base maintenance

The KB (`docs/kb/`) is how the next agent avoids re-learning this codebase. Keep it **true, short and linked to code**.

## Steps
1. List what your change did: `git diff --stat origin/main...HEAD` and `git status`.
2. For each item, update the matching page (see the table in `docs/kb/README.md`):
   - module, endpoint, table, job type, pipeline step → `architecture.md`
   - term → `domain/glossary.md`; dependency → `domain/context-map.md` + `ContextBoundariesTest`
   - rule enforced/tested → `domain/invariants.md`; security control or test → `security.md`
   - coding/testing/migration rule → `conventions.md`; decision → `decisions.md` (or an ADR)
   - something that cost time to debug → `lessons.md`
   - milestone progress, new debt, open questions → `status.md` (update its date)
3. Edit in place: replace outdated statements, delete what is no longer true. Don't append a "changelog".
4. Reference code with backticked repository paths (`services/api/...`), class names, or test names.
5. Run `python3 scripts/check-kb.py` — it fails on backticked paths that don't exist.

## If nothing needs updating
That is fine for pure refactors with no visible effect. Say so explicitly in your final message
("KB checked: no changes needed because …") so the reviewer knows it was considered.

## Audit mode
When asked to audit the KB: compare each page against the code (packages, migrations, endpoints, tests, workflows),
fix inaccuracies, and report what changed.
