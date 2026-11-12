#!/usr/bin/env bash
# Stop hook: if code changed (uncommitted, or committed but not pushed) and nothing under docs/kb/ changed,
# block the stop once and ask the agent to update the knowledge base or state why no update is needed.
input=$(cat)
# Second stop in a row (the agent already responded to this reminder): let it finish.
if printf '%s' "$input" | grep -Eq '"stop_hook_active"[[:space:]]*:[[:space:]]*true'; then exit 0; fi

root=$(git rev-parse --show-toplevel 2>/dev/null) || exit 0
cd "$root" || exit 0

changed=$(
  {
    git status --porcelain 2>/dev/null | cut -c4- | sed 's/.* -> //'
    if upstream=$(git rev-parse --abbrev-ref --symbolic-full-name '@{u}' 2>/dev/null); then
      git diff --name-only "$upstream"...HEAD 2>/dev/null
    fi
  } | sort -u
)
code=$(printf '%s\n' "$changed" | grep -E '^(services|apps|infra|scripts|\.github)/' || true)
kb=$(printf '%s\n' "$changed" | grep -E '^docs/kb/' || true)

if [ -n "$code" ] && [ -z "$kb" ]; then
  reason="Code changed but docs/kb/ did not. Follow the kb-maintenance skill: update the knowledge base pages your change made inaccurate (status.md, architecture.md, domain/*, security.md, conventions.md, lessons.md), run python3 scripts/check-kb.py, or state explicitly why no KB update is needed."
  printf '{"decision":"block","reason":"%s"}\n' "$reason"
fi
exit 0
