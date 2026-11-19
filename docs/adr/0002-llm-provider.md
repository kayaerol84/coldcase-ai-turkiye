# ADR 0002 — Language model provider for source-grounded Q&A (T3)

**Status:** Accepted 2026-11-19 for the public demo corpus (option A). Private case material: not decided — Q&A must stay
`none` in any environment holding non-public documents until B or C is accepted.
**Date:** 2026-11-19

## Context

T3 answers questions about a case from its documents (TR §6.3). Retrieval stays in our infrastructure
(ADR 0001); the answer is drafted by a language model from the **retrieved passages only**, then every citation is
checked against those passages in our code before anything is stored or shown.

What leaves our infrastructure per question, if a hosted model is used:
- the question;
- up to 12 retrieved passages (`app.qa.passages`): segment text, document title, PDF page, printed page label,
  speaker, source kind, OCR confidence;
- the fixed system prompt (`QaPrompt.SYSTEM`, version `qa-v1`).

Not sent: whole documents, other passages, user identities, case titles, embeddings, file names beyond the document title.

This differs from embeddings (ADR 0001), where the whole corpus would have had to leave. Here only the passages
relevant to one question do. They can still contain personal data and unverified allegations about named people,
so data residency, retention, KVKK cross-border transfer rules and the provider's terms apply.

Quality requirements that rule out small models: Turkish (incl. Ottoman-era vocabulary and OCR noise), verbatim
quoting, keeping who-said-what straight, abstaining rather than guessing, resisting instructions inside sources.

## Options

| | A. Claude via the Anthropic API | B. Claude via a cloud provider's EU region (Amazon Bedrock / Google Vertex AI) | C. Self-hosted open-weight model |
|---|---|---|---|
| Where passages are processed | Anthropic; `inference_geo` offers `global` (default) or `us` only — no EU-only option | The cloud provider's EU region, under the organisation's existing cloud agreement | Our infrastructure |
| Answer quality (Turkish, citations, abstention) | Strongest models available | Same model family; check that the chosen model is offered in an EU region before accepting | Noticeably weaker at faithful long-context Turkish citation; needs GPUs |
| Work in this codebase | Done (`AnthropicAnswerGenerator`) | New adapter behind the same `AnswerGenerator` port (the Java SDK has Bedrock and Vertex backends) | New adapter + GPU serving + evaluation |
| Cost at pilot scale | Low (per-question tokens) | Similar | Fixed GPU cost |

## Recommendation

- **Public demo corpus (TBMM transcripts, already public):** A is acceptable. Configure `LLM_PROVIDER=anthropic`
  and an API key; keep the organisation's data-retention settings in line with policy.
- **Private case material (pilots with real case files):** B in an EU region, or C if no hosted option is acceptable.
  Decide before the first non-public upload.

Whatever is chosen:
1. Only the adapter selected by `app.llm.provider` may send case content; adding a provider value needs an ADR update.
2. Every answer records `provider`, the **model that actually answered** (from the response), `promptVersion`,
   effort and token limits.
3. Server-side model fallbacks stay off: an answer must come from the configured model, and a refusal is stored as
   an abstention rather than retried elsewhere.
4. Outputs are AI drafts (`review_status = AI_DRAFT`) and only verbatim, validated citations survive.

## Decision

Accepted by the product owner on 2026-11-19:
- **Demo environment (public TBMM transcripts only): option A**, Claude via the Anthropic API, `LLM_PROVIDER=anthropic`.
- **Any environment with non-public material: undecided.** `LLM_PROVIDER` stays `none` there (the code default) until
  option B or C is accepted in an update to this ADR. Uploading private documents to the demo environment is not allowed
  while Q&A is enabled there.

## Consequences

- With `none`, `POST /cases/{id}/questions` returns `503 QA_UNAVAILABLE` and the web app says so; everything else works.
- With `anthropic`, `ANTHROPIC_API_KEY` must be set (startup fails otherwise). The key is a secret: environment or
  secret store only, never in the repo.
- Changing the model, prompt or schema changes stored answers' provenance: bump `QaPrompt.VERSION` for prompt or
  schema changes; the model is recorded per answer.
