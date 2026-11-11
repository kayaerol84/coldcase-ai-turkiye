# ADR 0001 — Embedding model for Turkish retrieval (T2)

**Status:** Proposed — final pick depends on a bake-off on the real Volume 2 corpus
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

## Open questions for the product owner

- **Is sending the full corpus to an external embedding API acceptable** (with an EU-region
  DPA), or must embedding stay on our own infrastructure? This decides whether API models are
  in the bake-off at all.
- GPU budget: is a GPU instance acceptable later if the bake-off shows it pays off?

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
