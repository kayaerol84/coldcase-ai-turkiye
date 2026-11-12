"use client";

import Link from "next/link";
import { useParams, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useMemo, useState } from "react";
import { AuthGate } from "@/components/AuthGate";
import { ErrorNote } from "@/components/ErrorNote";
import { HighlightedText } from "@/components/HighlightedText";
import { StatusLabel } from "@/components/StatusLabel";
import {
  api,
  Case,
  Doc,
  labelSourceLabels,
  Page,
  PageReport,
  PageReportKind,
  pageReportKindLabels,
  pageStatusLabels,
} from "@/lib/api";
import { termHighlights } from "@/lib/turkish";

function Viewer({ documentId, pageNumber }: { documentId: string; pageNumber: number }) {
  const params = useSearchParams();
  const q = params.get("q") ?? "";
  const start = params.get("start");
  const end = params.get("end");
  const focus = start != null && end != null ? { start: Number(start), end: Number(end) } : undefined;

  const [doc, setDoc] = useState<Doc | null>(null);
  const [kase, setCase] = useState<Case | null>(null);
  const [page, setPage] = useState<Page | null>(null);
  const [image, setImage] = useState<string | null>(null);
  const [imageError, setImageError] = useState<string | null>(null);
  const [reports, setReports] = useState<PageReport[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.getDocument(documentId).then((d) => {
      setDoc(d);
      api.getCase(d.caseId).then(setCase).catch(() => {});
    }).catch((e: Error) => setError(e.message));
    api.listReports(documentId).then(setReports).catch(() => {});
  }, [documentId]);

  useEffect(() => {
    setPage(null);
    setError(null);
    api.getPage(documentId, pageNumber).then(setPage).catch((e: Error) => setError(e.message));
    let url: string | null = null;
    setImage(null);
    setImageError(null);
    api.pageImageUrl(documentId, pageNumber)
      .then((u) => { url = u; setImage(u); })
      .catch((e: Error) => setImageError(e.message));
    return () => { if (url) URL.revokeObjectURL(url); };
  }, [documentId, pageNumber]);

  const text = page ? (page.method === "OCR" ? page.ocrText : page.embeddedText) ?? "" : "";
  const ranges = useMemo(() => (q ? termHighlights(text, q) : []), [text, q]);
  const total = doc?.file?.pageCount ?? 0;
  const keep = q ? `?q=${encodeURIComponent(q)}` : "";
  const canReport = kase && kase.myRole !== "VIEWER";
  const pageReports = reports.filter((r) => r.pdfPageNumber === pageNumber);

  if (error && !page) return <ErrorNote message={error} />;

  return (
    <>
      <p>
        <Link href={`/documents/${documentId}`}>← {doc?.title ?? "Belge"}</Link>
        {doc && <> · <Link href={`/cases/${doc.caseId}/search`}>Aramaya dön</Link></>}
      </p>
      <div className="row between viewer-head">
        <h1 className="h1">
          PDF sayfası {pageNumber}{total ? ` / ${total}` : ""}
          <span className="muted"> · basılı sayfa {page?.printedPageLabel ?? "bilinmiyor"}</span>
        </h1>
        <nav className="row" aria-label="Sayfa gezinme">
          {pageNumber > 1 ? (
            <Link className="btn" href={`/documents/${documentId}/pages/${pageNumber - 1}${keep}`}>← Önceki</Link>
          ) : <span className="btn" aria-disabled="true">← Önceki</span>}
          {!total || pageNumber < total ? (
            <Link className="btn" href={`/documents/${documentId}/pages/${pageNumber + 1}${keep}`}>Sonraki →</Link>
          ) : <span className="btn" aria-disabled="true">Sonraki →</span>}
        </nav>
      </div>
      {page && (
        <p>
          <StatusLabel text={pageStatusLabels[page.status].tr} tone={pageStatusLabels[page.status].tone} />
          <span className="muted small">
            {page.method === "OCR" ? `OCR (${page.ocrEngine ?? ""} ${page.ocrEngineVersion ?? ""})` : "Gömülü PDF metni"}
            {page.printedLabelSource && ` · sayfa etiketi ${labelSourceLabels[page.printedLabelSource] ?? page.printedLabelSource}`}
          </span>
        </p>
      )}
      {page?.method === "OCR" && (page.ocrConfidence ?? 0) < 0.75 && (
        <p className="warn-box">OCR güveni düşük (%{Math.round((page.ocrConfidence ?? 0) * 100)}). İsim, tarih ve alıntıları sayfa görüntüsünden doğrulayın.</p>
      )}

      <div className="viewer">
        <section className="card page-image" aria-label="Orijinal sayfa">
          <h2 className="h3">Orijinal sayfa</h2>
          {image ? (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={image} alt={`PDF sayfası ${pageNumber} görüntüsü`} />
          ) : imageError ? (
            <ErrorNote message={imageError} />
          ) : (
            <p className="muted">Sayfa görüntüsü yükleniyor…</p>
          )}
        </section>
        <section className="card" aria-label="Çıkarılan metin">
          <h2 className="h3">
            Çıkarılan metin
            {focus && <span className="label" data-tone="accent">Bulunan pasaj işaretli</span>}
          </h2>
          {page ? (
            text ? (
              <pre className="source-text tall"><HighlightedText text={text} ranges={ranges} focus={focus} /></pre>
            ) : (
              <p className="muted">Bu sayfada çıkarılmış metin yok.</p>
            )
          ) : (
            <p className="muted">Yükleniyor…</p>
          )}
          <p className="muted small">
            Bu metin otomatik çıkarılmıştır ve hata içerebilir. Alıntı yapmadan önce sayfa görüntüsüyle karşılaştırın.
          </p>
          {pageReports.length > 0 && (
            <div>
              <h3 className="h3">Bu sayfa için bildirimler</h3>
              <ul className="small">
                {pageReports.map((r) => (
                  <li key={r.id}>
                    {pageReportKindLabels[r.kind]}{r.note ? `: ${r.note}` : ""} <span className="muted">— {r.createdBy}, {new Date(r.createdAt).toLocaleDateString("tr-TR")}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}
          {canReport && page && (
            <ReportForm documentId={documentId} pageNumber={pageNumber} onReported={(r) => setReports((x) => [r, ...x])} />
          )}
        </section>
      </div>
    </>
  );
}

function ReportForm({ documentId, pageNumber, onReported }: { documentId: string; pageNumber: number; onReported: (r: PageReport) => void }) {
  const [open, setOpen] = useState(false);
  const [kind, setKind] = useState<PageReportKind>("OCR_ERROR");
  const [note, setNote] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);

  if (!open) {
    return <button className="btn" onClick={() => { setOpen(true); setDone(false); }}>Hata bildir</button>;
  }
  return (
    <form
      className="form report"
      onSubmit={async (e) => {
        e.preventDefault();
        setError(null);
        try {
          onReported(await api.reportPage(documentId, pageNumber, kind, note));
          setOpen(false);
          setNote("");
          setDone(true);
        } catch (err) {
          setError((err as Error).message);
        }
      }}
    >
      <label>
        Sorun
        <select value={kind} onChange={(e) => setKind(e.target.value as PageReportKind)}>
          {(Object.keys(pageReportKindLabels) as PageReportKind[]).map((k) => <option key={k} value={k}>{pageReportKindLabels[k]}</option>)}
        </select>
      </label>
      <label>
        Not
        <textarea rows={2} maxLength={2000} value={note} onChange={(e) => setNote(e.target.value)} placeholder="ör. 'Öztürk' yerine 'Ozturk' okunmuş" />
      </label>
      <ErrorNote message={error} />
      <div className="row">
        <button className="btn primary">Gönder</button>
        <button type="button" className="btn" onClick={() => setOpen(false)}>Vazgeç</button>
      </div>
      {done && <p className="small">Bildirildi.</p>}
    </form>
  );
}

export default function ViewerPage() {
  const { documentId, pageNumber } = useParams<{ documentId: string; pageNumber: string }>();
  return (
    <AuthGate>
      <Suspense fallback={<p className="muted">Yükleniyor…</p>}>
        <Viewer documentId={documentId} pageNumber={Number(pageNumber)} />
      </Suspense>
    </AuthGate>
  );
}
