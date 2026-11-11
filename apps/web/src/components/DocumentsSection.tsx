"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { ErrorNote } from "@/components/ErrorNote";
import { StatusLabel } from "@/components/StatusLabel";
import { api, ApiError, Doc, documentStatusLabels, formatBytes, PageCounts } from "@/lib/api";

export function pageSummary(p?: PageCounts, pageCount?: number): string {
  if (!p || p.total === 0) return pageCount ? `${pageCount} sayfa` : "—";
  const parts = [`${p.total} sayfa`];
  if (p.extracted) parts.push(`${p.extracted} metin`);
  if (p.ocrDone) parts.push(`${p.ocrDone} OCR`);
  if (p.needsOcr) parts.push(`${p.needsOcr} OCR bekliyor`);
  if (p.failed) parts.push(`${p.failed} başarısız`);
  return parts.join(" · ");
}

export function DocumentsSection({ caseId, canUpload }: { caseId: string; canUpload: boolean }) {
  const [docs, setDocs] = useState<Doc[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    api.listDocuments(caseId).then(setDocs).catch((e: Error) => setError(e.message));
  }, [caseId]);
  useEffect(load, [load]);

  // Processing is asynchronous: poll while anything is still being processed.
  const processing = docs?.some((d) => d.status === "PROCESSING");
  useEffect(() => {
    if (!processing) return;
    const t = setInterval(load, 3000);
    return () => clearInterval(t);
  }, [processing, load]);

  return (
    <section className="card">
      <h2 className="h2">Belgeler</h2>
      {canUpload && <UploadForm caseId={caseId} onUploaded={load} />}
      <ErrorNote message={error} />
      {docs?.length === 0 && <p className="muted">Bu dosyada henüz belge yok.</p>}
      {docs && docs.length > 0 && (
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th scope="col">Belge</th>
                <th scope="col">Durum</th>
                <th scope="col">Sayfalar</th>
                <th scope="col">Boyut</th>
              </tr>
            </thead>
            <tbody>
              {docs.map((d) => {
                const s = documentStatusLabels[d.status];
                return (
                  <tr key={d.id}>
                    <td>
                      <Link href={`/documents/${d.id}`}>{d.title}</Link>
                      {d.sourceName && <div className="muted small">{d.sourceName}</div>}
                    </td>
                    <td><StatusLabel text={s.tr} tone={s.tone} /></td>
                    <td className="small">{pageSummary(d.pages, d.file?.pageCount)}</td>
                    <td className="small">{d.file ? formatBytes(d.file.sizeBytes) : "—"}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function UploadForm({ caseId, onUploaded }: { caseId: string; onUploaded: () => void }) {
  const [file, setFile] = useState<File | null>(null);
  const [title, setTitle] = useState("");
  const [sourceName, setSourceName] = useState("");
  const [sourceDate, setSourceDate] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [duplicateOf, setDuplicateOf] = useState<string | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!file) return;
    setBusy(true);
    setError(null);
    setDuplicateOf(null);
    try {
      await api.uploadDocument(caseId, { file, title, sourceName, sourceDate });
      setFile(null);
      setTitle("");
      setSourceName("");
      setSourceDate("");
      if (fileInput.current) fileInput.current.value = "";
      onUploaded();
    } catch (err) {
      setError((err as Error).message);
      if (err instanceof ApiError && err.code === "DUPLICATE_DOCUMENT") {
        setDuplicateOf(String(err.problem.existingDocumentId ?? "") || null);
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="form upload" onSubmit={submit}>
      <label>
        PDF dosyası
        <input
          ref={fileInput}
          type="file"
          accept="application/pdf,.pdf"
          required
          onChange={(e) => setFile(e.target.files?.[0] ?? null)}
        />
      </label>
      <div className="row">
        <label className="grow">
          Başlık (isteğe bağlı)
          <input maxLength={500} value={title} onChange={(e) => setTitle(e.target.value)} placeholder="Dosya adından alınır" />
        </label>
        <label className="grow">
          Kaynak
          <input value={sourceName} onChange={(e) => setSourceName(e.target.value)} placeholder="ör. TBMM Araştırma Komisyonu" />
        </label>
        <label>
          Kaynak tarihi
          <input type="date" value={sourceDate} onChange={(e) => setSourceDate(e.target.value)} />
        </label>
      </div>
      <ErrorNote message={error} />
      {duplicateOf && <p className="small">Mevcut belge: <Link href={`/documents/${duplicateOf}`}>aç</Link></p>}
      <div>
        <button className="btn primary" disabled={busy || !file}>{busy ? "Yükleniyor…" : "Yükle"}</button>
        <span className="muted small" style={{ marginLeft: 12 }}>
          Orijinal dosya değiştirilmeden saklanır; metin çıkarma arka planda yapılır.
        </span>
      </div>
    </form>
  );
}
