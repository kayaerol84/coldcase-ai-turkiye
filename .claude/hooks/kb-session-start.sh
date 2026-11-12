#!/usr/bin/env bash
# SessionStart hook: put the knowledge-base protocol and current status in the agent's context.
root=$(git rev-parse --show-toplevel 2>/dev/null) || exit 0
status="$root/docs/kb/status.md"
echo "Knowledge base: read docs/kb/README.md and the pages for the area you work on before changing code, and update docs/kb/ in the same change (see CLAUDE.md)."
if [ -f "$status" ]; then
  echo
  echo "Current docs/kb/status.md:"
  cat "$status"
fi
exit 0
