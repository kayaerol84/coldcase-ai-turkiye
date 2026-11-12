# Ubiquitous language

Use these terms in code, API, database and UI. The UI is Turkish; code and API are English.
If you need a new term, add it here in the same change. Don't introduce synonyms.

## Workspace and access
| English (code) | Türkçe (UI) | Meaning |
|---|---|---|
| Case | Dosya | An investigation workspace; the unit of access control and data isolation |
| Case membership | Üyelik | A user's role in one case |
| Role: Viewer / Researcher / Reviewer / Case Admin | Görüntüleyici / Araştırmacı / İnceleyici / Dosya yöneticisi | `CaseRole`; maps to `Permission`s |
| Platform admin | Platform yöneticisi | Platform configuration only; **no** implicit case access |
| Audit event | Denetim kaydı | Append-only record of a security- or workflow-relevant action |

## Sources
| English (code) | Türkçe (UI) | Meaning |
|---|---|---|
| Document | Belge | Logical source record (title, source, date) |
| Document version / original | Orijinal dosya | Immutable uploaded bytes, identified by SHA-256 |
| Page | Sayfa | One PDF page; `pdf_page_number` is **1-based** |
| Printed page label | Basılı sayfa | Number printed on the page (e.g. transcript page 1427); never assumed equal to the PDF page |
| Embedded text | Gömülü PDF metni | Text stored in the PDF |
| OCR text | OCR metni | Text recognised from the page image; always kept separate from embedded text |
| Extraction quality / OCR confidence | Kalite / OCR güveni | Plausibility of extracted text — never evidence of truth |
| Segment (passage) | Pasaj | Retrieval chunk within one page, with char offsets into that page's text |
| Speaker | Konuşmacı | Person a transcript turn is attributed to (`BAŞKAN —`, `AD SOYAD (Kurum) —`) |
| Page report | Hata bildirimi | Researcher report of an extraction/OCR/label problem |
| Processing job | İşlem | Async unit of work: `EXTRACT_DOCUMENT`, `OCR_PAGES`, `INDEX_DOCUMENT` |
| Index | Arama dizini | Segments + full-text vectors + embeddings for a document version |

## Research (T3–T5, from the requirements; not yet implemented)
| English (code) | Türkçe (UI) | Meaning |
|---|---|---|
| Answer | Yanıt | AI-drafted answer, status `AI_DRAFT` until reviewed |
| Citation | Kaynak gösterimi | Link from an answer/finding/report to a segment; must map to a retrieved segment |
| Statement | Beyan | Attributed claim extracted from a source, qualifiers preserved |
| Statement comparison | Beyan karşılaştırması | Reviewed relationship between statements (TR §7.2 categories) |
| Finding | Bulgu | Research conclusion with source references and a classification label |
| Research lead | Araştırma ipucu | Follow-up question or missing-document task |
| Annotation | Not | Researcher note on a source or finding |
| Report | Rapor | Exported, source-traceable write-up |

## Finding classification labels (BR §9) — never a single numeric score
Source statement · Documented fact · Corroborated · Disputed · Unverified allegation · Research lead ·
Missing from corpus · Formally requested, not received · Inconclusive.
Turkish labels: `apps/web/src/lib/labels.ts`.

## Words to avoid
"Evidence" for search results or AI output (use *passage*, *source*, *suggestion*); "confidence" for anything
about a person; "contradiction" unless propositions are materially incompatible (TR §7.2).
