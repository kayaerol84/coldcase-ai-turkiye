"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { AuthGate } from "@/components/AuthGate";
import { pageSummary } from "@/components/DocumentsSection";
import { ErrorNote } from "@/components/ErrorNote";
import { StatusLabel } from "@/components/StatusLabel";
import {
  api,
  Case,
  Doc,
  documentStatusLabels,
  formatBytes,
  labelSourceLabels,
  Page,
  pageStatusLabels,
  statusDetailLabels,
} from "@/lib/api";

const LOW_QUALITY = 0.75;

function needsAttention(p: Page) {
  return p.status !== "EXTRACTED" && p.status !== "OCR_DONE" ? true : (p.quality ?? 1) < LOW_QUALITY;
}

function DocumentView({ documentId }: { documentId: string }) {
  const [doc, setDoc] = useState<Doc | null>(null);
  const [kase, setCase] = useState<Case | null>(null);
  const [pages, setPages] = useState<Page[] | null>(null);
  const [selected, setSelected] = useState<number | null>(null);
  const [issuesOnly, setIssuesOnly] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const d = await api.getDocument(documentId);
      setDoc(d);
      setPages(await api.listPages(documentId));
      return d;
    } catch (e) {
      setError((e as Error).message);
    }
  }, [documentId]);

  useEffect(() => {
    load().then((d) => d && api.getCase(d.caseId).then(setCase).catch(() => {}));
  }, [load]);

  useEffect(() => {
    if (doc?.status !== "PROCESSING") return;
    const t = setInterval(load, 3000);
    return () => clearInterval(t);
  }, [doc?.status, load]);

  if (error && !doc) return <ErrorNote message={error} />;
  if (!doc) return <p className="muted" role="status">Yükleniyor…</p>;

  const canManage = kase?.myRole === "CASE_ADMIN";
  const status = documentStatusLabels[doc.status];
  const shown = (pages ?? []).filter((p) => !issuesOnly || needsAttention(p));
  const attention = (pages ?? []).filter(needsAttention).length;

  async function reprocess(pageNumbers?: number[]) {
    setError(null);
    try {
      setDoc(await api.reprocess(documentId, pageNumbers));
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function openOriginal() {
    setError(null);
    try {
      const url = await api.originalFileUrl(documentId, true);
      window.open(url, "_blank", "noopener");
      setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <>
      <p><Link href={`/cases/${doc.caseId}`}>← {kase?.title ?? "Dosya"}</Link></p>
      <section className="card">
        <div className="row between">
          <h1 className="h1">{doc.title}</h1>
          <div className="row">
            <button className="btn" onClick={openOriginal}>Orijinali aç</button>
            {canManage && (
              <button className="btn" disabled={doc.status === "PROCESSING"} onClick={() => reprocess()}>
                Yeniden işle
              </button>
            )}
          </div>
        </div>
        <p>
          <StatusLabel text={status.tr} tone={status.tone} />
          {doc.statusDetail && <span className="muted small">{statusDetailLabels[doc.statusDetail] ?? doc.statusDetail}</span>}
        </p>
        <dl className="meta">
          {doc.sourceName && (<><dt>Kaynak</dt><dd>{doc.sourceName}{doc.sourceDate ? ` (${doc.sourceDate})` : ""}</dd></>)}
          <dt>Sayfalar</dt>
          <dd>{pageSummary(doc.pages, doc.file?.pageCount)}</dd>
          {doc.file && (
            <>
              <dt>Dosya</dt>
              <dd>{doc.file.originalFilename ?? "—"} · {formatBytes(doc.file.sizeBytes)}</dd>
              <dt>SHA-256</dt>
              <dd className="mono break">{doc.file.sha256}</dd>
              <dt>Yüklenme</dt>
              <dd>{new Date(doc.file.uploadedAt).toLocaleString("tr-TR")}</dd>
              <dt>Arama dizini</dt>
              <dd>
                {doc.index?.status === "INDEXED"
                  ? `${doc.index.segmentCount ?? 0} pasaj · ${doc.index.embeddingModel ? `anlamsal: ${doc.index.embeddingModel}` : "yalnızca anahtar kelime"}`
                  : "henüz dizinlenmedi"}
              </dd>
            </>
          )}
        </dl>
        <ErrorNote message={error} />
      </section>

      <div className="split">
        <section className="card">
          <p className="muted small">Sayfa numarasına tıklayarak orijinal sayfayı ve metni yan yana açın.</p>
          <div className="row between">
            <h2 className="h2">Sayfalar</h2>
            <label className="check">
              <input type="checkbox" checked={issuesOnly} onChange={(e) => setIssuesOnly(e.target.checked)} />
              Yalnızca inceleme gerektirenler ({attention})
            </label>
          </div>
          {pages?.length === 0 && <p className="muted">Sayfalar henüz çıkarılmadı.</p>}
          {shown.length > 0 && (
            <div className="table-wrap pages-scroll">
              <table className="table">
                <thead>
                  <tr>
                    <th scope="col">PDF s.</th>
                    <th scope="col">Basılı s.</th>
                    <th scope="col">Durum</th>
                    <th scope="col">Kalite</th>
                  </tr>
                </thead>
                <tbody>
                  {shown.map((p) => {
                    const s = pageStatusLabels[p.status];
                    return (
                      <tr
                        key={p.id}
                        className={selected === p.pdfPageNumber ? "selected" : undefined}
                        onClick={() => setSelected(p.pdfPageNumber)}
                      >
                        <td>
                          <Link href={`/documents/${documentId}/pages/${p.pdfPageNumber}`} onClick={(e) => e.stopPropagation()}>
                            {p.pdfPageNumber}
                          </Link>
                        </td>
                        <td>{p.printedPageLabel ?? <span className="muted">—</span>}</td>
                        <td><StatusLabel text={s.tr} tone={s.tone} /></td>
                        <td className="small">
                          {p.quality != null ? `%${Math.round(p.quality * 100)}` : "—"}
                          {p.quality != null && p.quality < LOW_QUALITY && <span className="warn-text"> düşük</span>}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </section>
        <PagePanel
          documentId={documentId}
          pageNumber={selected}
          canManage={canManage}
          busy={doc.status === "PROCESSING"}
          onReprocess={(n) => reprocess([n])}
          refreshKey={doc.updatedAt}
        />
      </div>
    </>
  );
}

function PagePanel({
  documentId,
  pageNumber,
  canManage,
  busy,
  onReprocess,
  refreshKey,
}: {
  documentId: string;
  pageNumber: number | null;
  canManage: boolean;
  busy: boolean;
  onReprocess: (n: number) => void;
  refreshKey: string;
}) {
  const [page, setPage] = useState<Page | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (pageNumber == null) return;
    setError(null);
    api.getPage(documentId, pageNumber).then(setPage).catch((e: Error) => setError(e.message));
  }, [documentId, pageNumber, refreshKey]);

  if (pageNumber == null) {
    return (
      <section className="card">
        <p className="muted">Çıkarılan metni görmek için bir sayfa seçin.</p>
      </section>
    );
  }
  if (error) return <section className="card"><ErrorNote message={error} /></section>;
  if (!page || page.pdfPageNumber !== pageNumber) return <section className="card"><p className="muted">Yükleniyor…</p></section>;

  const s = pageStatusLabels[page.status];
  return (
    <section className="card" aria-live="polite">
      <div className="row between">
        <h2 className="h2">
          PDF sayfası {page.pdfPageNumber}
          <span className="muted"> · basılı sayfa {page.printedPageLabel ?? "bilinmiyor"}</span>
        </h2>
        {canManage && (
          <button className="btn" disabled={busy} onClick={() => onReprocess(page.pdfPageNumber)}>
            OCR ile yeniden işle
          </button>
        )}
      </div>
      <p>
        <StatusLabel text={s.tr} tone={s.tone} />
        {page.printedLabelSource && <span className="muted small">Sayfa etiketi {labelSourceLabels[page.printedLabelSource] ?? page.printedLabelSource}</span>}
      </p>
      {page.status === "NEEDS_OCR" && (
        <p className="warn-box">Bu sayfada okunabilir gömülü metin yok veya metin bozuk. OCR yapılana kadar aramada yer almaz.</p>
      )}
      {page.method === "OCR" && !page.ocrText && (
        <p className="warn-box">OCR bu sayfada metin bulamadı; sayfa boş olabilir. Orijinal sayfadan doğrulayın.</p>
      )}
      {page.method === "OCR" && !!page.ocrText && (page.ocrConfidence ?? 0) < LOW_QUALITY && (
        <p className="warn-box">
          OCR güveni düşük (%{Math.round((page.ocrConfidence ?? 0) * 100)}). İsim, tarih ve alıntıları orijinal sayfadan doğrulayın.
        </p>
      )}
      {page.error && <p className="warn-box">Hata: {page.error}</p>}

      <TextBlock title="Gömülü PDF metni" text={page.embeddedText} active={page.method === "EMBEDDED_TEXT"} />
      {(page.ocrText != null || page.method === "OCR") && (
        <TextBlock
          title={`OCR metni${page.ocrEngine ? ` (${page.ocrEngine} ${page.ocrEngineVersion ?? ""})` : ""}`}
          text={page.ocrText}
          active={page.method === "OCR"}
        />
      )}
      <p className="muted small">Çıkarıcı: {page.extractorVersion}</p>
    </section>
  );
}

function TextBlock({ title, text, active }: { title: string; text?: string; active: boolean }) {
  return (
    <div className="textblock">
      <h3 className="h3">
        {title} {active && <span className="label" data-tone="accent">Kullanılan metin</span>}
      </h3>
      {text ? <pre className="source-text">{text}</pre> : <p className="muted small">Metin yok.</p>}
    </div>
  );
}

export default function DocumentPage() {
  const { documentId } = useParams<{ documentId: string }>();
  return (
    <AuthGate>
      <DocumentView documentId={documentId} />
    </AuthGate>
  );
}
