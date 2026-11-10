# Evaluation benchmark

Curated questions over the demonstration corpus (TBMM Uğur Mumcu commission, Volume 2).
Each item (TR §15): question, expected document + PDF page(s) + printed page label(s),
relevant passage, expected answer points, known ambiguity, acceptable abstention.

No model, prompt, embedding or retrieval change ships without running this benchmark.

Format: one YAML file per question in `questions/`. Seed topics come from BR §8.2–8.3.
