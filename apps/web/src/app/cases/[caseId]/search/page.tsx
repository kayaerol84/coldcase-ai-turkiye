"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { AuthGate } from "@/components/AuthGate";
import { ErrorNote } from "@/components/ErrorNote";
import { HighlightedText } from "@/components/HighlightedText";
import { api, Case, Doc, matchLabels, SearchMode, searchModeLabels, SearchResult } from "@/lib/api";

const modes: SearchMode[] = ["HYBRID", "KEYWORD", "SEMANTIC", "EXACT"];

const modeHelp: Record<SearchMode, string> = {
  HYBRID: "Anahtar kelime ve anlam benzerliğini birleştirir. Tırnak içindeki ifadeler öne çıkar.",
  KEYWORD: "Tüm kelimeler geçmeli. \"tam ifade\" için tırnak, hariç tutmak için -kelime kullanın.",
  SEMANTIC: "Farklı kelimelerle anlatılmış benzer pasajları bulur.",
  EXACT: "İfadenin aynen geçtiği pasajlar (büyük/küçük harf ve Türkçe karakter farkı gözetilmez).",
};

function SearchView({ caseId }: { caseId: string }) {
  const [kase, setCase] = useState<Case | null>(null);
  const [docs, setDocs] = useState<Doc[]>([]);
  const [query, setQuery] = useState("");
  const [mode, setMode] = useState<SearchMode>("HYBRID");
  const [docFilter, setDocFilter] = useState("");
  const [pageFrom, setPageFrom] = useState("");
  const [pageTo, setPageTo] = useState("");
  const [speaker, setSpeaker] = useState("");
  const [result, setResult] = useState<SearchResult | null>(null);
  const [submitted, setSubmitted] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.getCase(caseId).then(setCase).catch((e: Error) => setError(e.message));
    api.listDocuments(caseId).then(setDocs).catch(() => {});
  }, [caseId]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!query.trim()) return;
    setBusy(true);
    setError(null);
    try {
      setResult(
        await api.search(caseId, query, mode, {
          documentIds: docFilter ? [docFilter] : undefined,
          pdfPageFrom: pageFrom ? Number(pageFrom) : undefined,
          pdfPageTo: pageTo ? Number(pageTo) : undefined,
          speaker: speaker || undefined,
        }),
      );
      setSubmitted(query);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const notSearchable = docs.filter((d) => d.index?.status !== "INDEXED");

  return (
    <>
      <p><Link href={`/cases/${caseId}`}>← {kase?.title ?? "Dosya"}</Link></p>
      <h1 className="h1">Belgelerde ara</h1>
      <form className="card form" onSubmit={submit} role="search">
        <label>
          Arama
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            maxLength={500}
            placeholder='ör. Telefon kayıtları neden komisyona ulaşmadı?  ya da  "teslim etmedim"'
            autoFocus
          />
        </label>
        <fieldset className="modes">
          <legend className="muted small">Arama türü</legend>
          {modes.map((m) => (
            <label key={m} className="check">
              <input type="radio" name="mode" value={m} checked={mode === m} onChange={() => setMode(m)} />
              {searchModeLabels[m]}
            </label>
          ))}
        </fieldset>
        <p className="muted small">{modeHelp[mode]}</p>
        <details>
          <summary className="small">Filtreler</summary>
          <div className="row" style={{ marginTop: 12 }}>
            <label className="grow">
              Belge
              <select value={docFilter} onChange={(e) => setDocFilter(e.target.value)}>
                <option value="">Tüm belgeler</option>
                {docs.map((d) => <option key={d.id} value={d.id}>{d.title}</option>)}
              </select>
            </label>
            <label>
              PDF sayfası (başlangıç)
              <input type="number" min={1} value={pageFrom} onChange={(e) => setPageFrom(e.target.value)} />
            </label>
            <label>
              PDF sayfası (bitiş)
              <input type="number" min={1} value={pageTo} onChange={(e) => setPageTo(e.target.value)} />
            </label>
            <label className="grow">
              Konuşmacı
              <input value={speaker} onChange={(e) => setSpeaker(e.target.value)} placeholder="ör. Öztürk" />
            </label>
          </div>
        </details>
        <div>
          <button className="btn primary" disabled={busy || !query.trim()}>{busy ? "Aranıyor…" : "Ara"}</button>
        </div>
      </form>

      {notSearchable.length > 0 && (
        <p className="warn-box">
          {notSearchable.length} belge henüz aramaya dahil değil (işleniyor veya dizinlenemedi).
        </p>
      )}
      <ErrorNote message={error} />

      {result && (
        <section aria-live="polite">
          {!result.semanticAvailable && (result.mode === "HYBRID" || result.mode === "SEMANTIC") && (
            <p className="warn-box">Anlamsal arama şu anda kullanılamıyor; yalnızca anahtar kelime sonuçları gösteriliyor.</p>
          )}
          <p className="muted small">
            {result.results.length} sonuç · {searchModeLabels[result.modeUsed]} · {result.tookMs} ms
            {result.embeddingModel ? ` · model: ${result.embeddingModel}` : ""}
          </p>
          <p className="muted small">
            Sonuçlar kanıt değildir: sıralama yalnızca benzerliği gösterir. Her pasajı kaynağında açıp bağlamıyla doğrulayın.
          </p>
          {result.results.length === 0 && <p className="card muted">Sonuç bulunamadı. Farklı kelimeler veya “Karma” aramayı deneyin.</p>}
          {result.results.map((h) => (
            <article key={h.segmentId} className="card hit">
              <header className="hit-head">
                <Link
                  href={`/documents/${h.documentId}/pages/${h.pdfPageNumber}?q=${encodeURIComponent(submitted)}&start=${h.charStart}&end=${h.charEnd}`}
                  className="hit-title"
                >
                  {h.documentTitle}
                </Link>
                <span className="muted small">
                  PDF s. {h.pdfPageNumber} · basılı s. {h.printedPageLabel ?? "—"}
                  {h.speaker && <> · {h.speaker}{h.speakerFromPreviousPage && " (önceki sayfadan devam)"}</>}
                </span>
              </header>
              <p className="source-snippet">
                <HighlightedText text={h.text} ranges={h.highlights} />
              </p>
              <footer className="row small">
                {h.matchedBy.map((m) => <span key={m} className="label" data-tone="neutral">{matchLabels[m]}</span>)}
                {h.sourceKind === "OCR" && (
                  <span className="label" data-tone="warning">
                    OCR metni{h.ocrConfidence != null ? ` · güven %${Math.round(h.ocrConfidence * 100)}` : ""}
                  </span>
                )}
                <details className="diag">
                  <summary>Tanılama</summary>
                  <span className="mono">
                    kelime sırası {h.diagnostics.lexicalRank ?? "—"} · anlam sırası {h.diagnostics.vectorRank ?? "—"}
                    {h.diagnostics.vectorSimilarity != null && ` · benzerlik ${h.diagnostics.vectorSimilarity.toFixed(3)}`}
                    {` · birleşik ${h.diagnostics.fusedScore.toFixed(4)}`}
                  </span>
                </details>
              </footer>
            </article>
          ))}
        </section>
      )}
    </>
  );
}

export default function SearchPage() {
  const { caseId } = useParams<{ caseId: string }>();
  return (
    <AuthGate>
      <SearchView caseId={caseId} />
    </AuthGate>
  );
}
