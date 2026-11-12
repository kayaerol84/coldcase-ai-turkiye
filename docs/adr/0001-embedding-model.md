# ADR 0001 — Embedding model for Turkish retrieval (T2)

**Status:** Accepted 2026-11-12 — self-hosted only; bge-m3 is the default, the bake-off
compares self-hosted candidates only
**Date:** 2026-11-11

## Context

T2 adds hybrid search: PostgreSQL full-text search (lexical) plus vector search over
`text_segments`, fused with reciprocal rank fusion. The vector side needs an embedding model.
Constraints from the requirements:

- **Turkish first** (BR §11): agglutinative morphology (`kayıt` / `kayıtlarının`), names that
  appear with and without diacritics (`Özdemir` / `Ozdemir`), OCR noise on scanned pages.
- **Chunks of ~400–800 tokens** (TR §5.3), so the model's input limit must exceed that.
- **Sensitive material** (TR §12.2): embedding needs the *entire corpus*, not just retrieved
  passages, so an external API means sending every page to a third party. Data residency,
  DPAs and Turkish KVKK cross-border transfer rules apply.
- **pgvector:** HNSW indexes take at most 2,000 dimensions for `vector` (4,000 for `halfvec`).
- **Provider abstraction and versioning** (TR §3.7–3.8): the model id is stored with every
  vector, and re-embedding with another model must be routine.
- **Scale is small:** one 870-page volume is roughly 0.5M tokens. Even 50 volumes are about
  25M tokens, so cost is not the deciding factor for either API or self-hosted.

## Options

| Option | Hosting | Size / dims / max input | Licence | Turkish evidence | Fit |
|---|---|---|---|---|---|
| **BAAI bge-m3** | Self-host, CPU OK | 568M / 1024 / 8192 tok | MIT | Best of 5 in a small Turkish Q&A test (76% top-1); consistently strong in Turkish studies | ✅ Strong default: long input, no data leaves our infra |
| **Qwen3-Embedding-0.6B** | Self-host, CPU OK | 0.6B / up to 1024 / 32k tok | Apache-2.0 | Family leads TR-TEB overall (the 8B); 0.6B untested on Turkish in the sources found | ✅ Main challenger |
| **Qwen3-Embedding-4B / 8B** | Self-host, **needs GPU** | 4B–8B / up to 2560–4096 (truncate to ≤2000 for HNSW) / 32k | Apache-2.0 | 8B is top overall on TR-TEB | ⚠️ Best quality ceiling, but a GPU costs more to run than the whole MVP otherwise |
| **multilingual-e5-large(-instruct)** | Self-host, CPU OK | 560M / 1024 / **512 tok** | MIT | Nominally best in a 2026 Turkish RAG study, not significantly | ❌ 512-token limit conflicts with 400–800-token chunks |
| **Voyage voyage-4 / -4-large** | API (Voyage / MongoDB) | 1024 default (256–2048) / 32k | Commercial (~$0.06 / $0.12 per 1M tokens, aggregator prices) | Marketed as multilingual; no Turkish benchmark found | ⚠️ Good quality reference; whole corpus leaves our infra |
| **Cohere embed-v4** | API, incl. AWS Bedrock **EU geo inference** | up to 1536 | Commercial | 100+ languages; no Turkish benchmark found | ⚠️ Best API option if an EU-resident API is acceptable |
| Turkish-only models (e.g. TurkEmbed) | Self-host | small | varies | No significant advantage over multilingual models in the 2026 Turkish RAG study | ❌ No measured benefit; smaller ecosystem |

## Proposal

1. **Default: `BAAI/bge-m3`, self-hosted in the EU** behind the `EmbeddingProvider` adapter.
   Serve it with a CPU container (e.g. Hugging Face Text Embeddings Inference) next to the
   worker. Store the 1024-dimension dense vector in pgvector (HNSW, cosine). Lexical matching
   stays in PostgreSQL FTS, so the model's sparse output isn't needed.
2. **Run a bake-off before committing**, on the real Volume 2 PDF with the `evals/` question set.
   Candidates: bge-m3, Qwen3-Embedding-0.6B, and one API model as a quality ceiling
   (voyage-4-large, or Cohere embed-v4 via Bedrock EU), the API only if data policy allows.
   - Metrics: expected-page recall@10 and MRR, for **hybrid** retrieval (FTS + vector, RRF),
     not vector-only.
   - Slices: exact names and dates; paraphrased questions; names without diacritics;
     OCR'd pages; morphological variants.
   - Rule: take the best hybrid recall@10. If within ~2 points, take the self-hosted or
     cheaper model.
3. **Escalate to Qwen3-Embedding-4B/8B (GPU) only if** the bake-off shows a material recall gap
   that the 0.6B and bge-m3 don't close.
4. **Schema:** `embeddings(text_segment_id, model_id, model_version, dims, vector)` with
   `(text_segment_id, model_id)` unique, so a new model can be backfilled and compared side by
   side before switching. Every search and answer logs which model produced its vectors.

## Decision

The product owner decided embeddings stay on our own infrastructure: no external embedding
API, including EU-hosted ones. Voyage and Cohere drop out of the bake-off; candidates are
bge-m3 and Qwen3-Embedding-0.6B (and 4B/8B if a GPU is approved later).

## Security notes for self-hosting

Self-hosting removes the largest risk (the whole corpus going to a third party), but adds
a few of its own. How each is handled:

| Risk | Mitigation |
|---|---|
| **Model supply chain:** weights downloaded from Hugging Face could be swapped or malicious (pickle files can run code). | Pin the model commit (`EMBEDDINGS_MODEL_REVISION`) and the TEI image by digest, and check that the pinned revision is served from safetensors weights (pickle-format `.bin` files can run code when loaded). TEI doesn't execute code from the model repository. The API checks `/info` and refuses to embed if the served model or revision differs. In production, mirror the pinned weights into our own registry and run with no outbound internet. |
| **The embedding server receives all document text.** | Internal network only (localhost in dev; no ingress in prod); shared API key; our client never logs text. Before production, confirm from the server's own logs that request bodies aren't logged at the configured log level. |
| **Vectors leak content:** embedding inversion can partly reconstruct text from vectors. | Treat vectors as sensitive as the text: same database, encryption at rest, `case_id` on every row, deleted with their segments (cascade), covered by retention and backups like the text. Vectors are never returned by the API. |
| **Cross-case leakage through vector search** (approximate indexes filter *after* finding neighbours). | Exact scan restricted to one case in SQL before ranking; no global approximate index. Tested per search mode. |
| **Query privacy:** what researchers search for is itself sensitive. | Queries aren't logged or audited; metrics record only timings and empty-result counts. |
| **Denial of service:** embedding is CPU-heavy. | Query length capped (500 chars), batch indexing in the worker, the API degrades to keyword search when the server is unavailable. Rate limiting is planned for T6. |
| **Poisoned documents** crafted to rank highly for certain questions. | Only Case Admins upload; every result shows its source page for checking; prompt-injection defences for generation come in T3. |

## Evidence and caveats

Gathered by web search on 2026-11-11. Primary papers (arXiv, ACL Anthology) were not readable
from the build environment, so the figures are from search summaries. They are directional,
not verified, and none of them is our corpus, which is why the bake-off decides.

- Small Turkish Q&A benchmark, bge-m3 best of 5: https://github.com/ezgiieyice/ensemble-embeddings-semantic-search
- TR-TEB (Qwen3-Embedding-8B top overall; ME5 family strong): https://www.researchgate.net/publication/407438143_TR-TEB_Turkish_Text_Embedding_Benchmark
- TR-MTEB: https://aclanthology.org/2025.findings-emnlp.471.pdf
- Comparing chunking and embedding strategies for Turkish RAG (2026): https://arxiv.org/pdf/2608.26192
- TurkColBERT (late interaction for Turkish IR): https://arxiv.org/pdf/2511.16528
- Qwen3 Embedding paper: https://arxiv.org/pdf/2506.05176
- Voyage 4 listing and prices (aggregators): https://openrouter.ai/voyageai/voyage-4-large, https://costbench.com/software/embedding-apis/voyage-ai/
- Cohere embed-v4 EU geo inference on Bedrock: https://docs.aws.amazon.com/de_de/bedrock/latest/userguide/model-card-cohere-embed-v4.html
