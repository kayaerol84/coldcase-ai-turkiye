"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { AuthGate } from "@/components/AuthGate";
import { ErrorNote } from "@/components/ErrorNote";
import {
  abstainReasonLabels,
  Answer,
  AnswerSummary,
  api,
  Case,
  Citation,
  citationReportKindLabels,
  CitationReportKind,
  claimKindLabels,
  limitationLabels,
} from "@/lib/api";

const reportKinds = Object.keys(citationReportKindLabels) as CitationReportKind[];

function viewerHref(c: Citation) {
  return `/documents/${c.documentId}/pages/${c.pdfPageNumber}?start=${c.quoteStart}&end=${c.quoteEnd}`;
}

function CitationChip({ answerId, c, onReported }: { answerId: string; c: Citation; onReported: () => void }) {
  const [open, setOpen] = useState(false);
  const [kind, setKind] = useState<CitationReportKind>("DOES_NOT_SUPPORT_CLAIM");
  const [note, setNote] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function report(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.reportCitation(answerId, c.id, kind, note || undefined);
      setOpen(false);
      setNote("");
      onReported();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <li className="citation">
      <blockquote className="source-snippet">“{c.quote}”</blockquote>
      <div className="row small">
        <Link href={viewerHref(c)}>
          {c.documentTitle} · PDF s. {c.pdfPageNumber} · basılı s. {c.printedPageLabel ?? "—"}
        </Link>
        {c.speaker && <span className="muted">· {c.speaker}</span>}
        {c.sourceKind === "OCR" && <span className="label" data-tone="warning">OCR metni</span>}
        {c.reportCount > 0 && <span className="label" data-tone="critical">{c.reportCount} bildirim</span>}
        <button type="button" className="btn small" onClick={() => setOpen(!open)} aria-expanded={open}>
          Alıntıyı bildir
        </button>
      </div>
      {open && (
        <form className="form" onSubmit={report}>
          <label>
            Sorun
            <select value={kind} onChange={(e) => setKind(e.target.value as CitationReportKind)}>
              {reportKinds.map((k) => <option key={k} value={k}>{citationReportKindLabels[k]}</option>)}
            </select>
          </label>
          <label>
            Not (isteğe bağlı)
            <textarea value={note} onChange={(e) => setNote(e.target.value)} maxLength={2000} rows={2} />
          </label>
          <div><button className="btn" disabled={busy}>{busy ? "Gönderiliyor…" : "Bildir"}</button></div>
          <ErrorNote message={error} />
        </form>
      )}
    </li>
  );
}

function AnswerView({ answer, onReported }: { answer: Answer; onReported: () => void }) {
  const systemLimitations = answer.limitations.filter((l) => l.source === "SYSTEM");
  const modelLimitations = answer.limitations.filter((l) => l.source === "MODEL");
  return (
    <section className="card" aria-live="polite">
      <header className="row between">
        <h2 className="h2" style={{ margin: 0 }}>{answer.question}</h2>
        <span className="label" data-tone="warning">Yapay zekâ taslağı — doğrulanmadı</span>
      </header>
      <p className="muted small">
        Bu yanıt yalnızca dosyadaki pasajlardan üretilmiş bir taslaktır; bulgu değildir. Her ifadeyi alıntısının
        kaynağında, bağlamıyla birlikte doğrulayın. Beyanlar, konuşmacının söyledikleridir; doğruluğu ayrıca değerlendirilmelidir.
      </p>

      {answer.outcome === "ABSTAINED" && (
        <p className="warn-box">
          Yanıt verilmedi: {answer.abstainReason ? abstainReasonLabels[answer.abstainReason] : "yeterli kaynak yok."}
        </p>
      )}

      {answer.claims.length > 0 && (
        <ol className="claims">
          {answer.claims.map((cl) => (
            <li key={cl.id} className="claim">
              <p>
                <span className="label" data-tone={cl.kind === "SOURCE_STATEMENT" ? "accent" : "neutral"}>
                  {claimKindLabels[cl.kind]}{cl.speaker ? `: ${cl.speaker}` : ""}
                </span>
                {cl.text}
              </p>
              <ul className="citations">
                {cl.citations.map((c) => <CitationChip key={c.id} answerId={answer.id} c={c} onReported={onReported} />)}
              </ul>
            </li>
          ))}
        </ol>
      )}

      {answer.conflicts.length > 0 && (
        <>
          <h3 className="h3">Kaynaklar arasındaki farklar</h3>
          <ul>
            {answer.conflicts.map((cf, i) => (
              <li key={i}>
                {cf.description}{" "}
                <span className="small">
                  {cf.passages.map((p) => (
                    <Link key={p.segmentId} href={`/documents/${p.documentId}/pages/${p.pdfPageNumber}`} style={{ marginRight: 8 }}>
                      {p.documentTitle} s. {p.pdfPageNumber}
                    </Link>
                  ))}
                </span>
              </li>
            ))}
          </ul>
        </>
      )}

      {(modelLimitations.length > 0 || systemLimitations.length > 0) && (
        <>
          <h3 className="h3">Sınırlamalar</h3>
          <ul className="small">
            {systemLimitations.map((l) => (
              <li key={l.code}>{limitationLabels[l.code] ?? l.code}{l.count != null ? ` (${l.count})` : ""}</li>
            ))}
            {modelLimitations.map((l, i) => <li key={i}>{l.text}</li>)}
          </ul>
        </>
      )}

      {answer.suggestedLead && (
        <p className="small"><strong>Araştırma önerisi:</strong> {answer.suggestedLead}</p>
      )}

      <details className="diag">
        <summary className="small">Nasıl üretildi</summary>
        <p className="mono small">
          {answer.retrievedPassages} pasaj · {answer.generator.provider ?? "—"} / {answer.generator.model ?? "model çağrılmadı"}
          {" · istem "}{answer.generator.promptVersion ?? "—"}
          {answer.validation.citationsProposed != null &&
            ` · alıntı ${answer.validation.citationsKept}/${answer.validation.citationsProposed} doğrulandı`}
          {" · "}{new Date(answer.createdAt).toLocaleString("tr-TR")} · {answer.askedBy.displayName ?? ""}
        </p>
      </details>
    </section>
  );
}

function AskView({ caseId }: { caseId: string }) {
  const [kase, setCase] = useState<Case | null>(null);
  const [available, setAvailable] = useState<boolean | null>(null);
  const [question, setQuestion] = useState("");
  const [answer, setAnswer] = useState<Answer | null>(null);
  const [history, setHistory] = useState<AnswerSummary[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadHistory = useCallback(() => {
    api.listAnswers(caseId).then(setHistory).catch(() => {});
  }, [caseId]);

  useEffect(() => {
    api.getCase(caseId).then(setCase).catch((e: Error) => setError(e.message));
    api.qaStatus().then((s) => setAvailable(s.available)).catch(() => setAvailable(false));
    loadHistory();
  }, [caseId, loadHistory]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!question.trim()) return;
    setBusy(true);
    setError(null);
    try {
      setAnswer(await api.ask(caseId, question));
      loadHistory();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function open(id: string) {
    setError(null);
    try {
      setAnswer(await api.getAnswer(id));
    } catch (err) {
      setError((err as Error).message);
    }
  }

  const refresh = () => answer && open(answer.id);

  return (
    <>
      <p><Link href={`/cases/${caseId}`}>← {kase?.title ?? "Dosya"}</Link></p>
      <h1 className="h1">Belgelere soru sor</h1>
      {available === false && (
        <p className="warn-box">
          Soru-cevap bu kurulumda etkin değil (dil modeli sağlayıcısı yapılandırılmadı). Geçmiş yanıtlar görüntülenebilir.
        </p>
      )}
      <form className="card form" onSubmit={submit}>
        <label>
          Soru
          <textarea
            value={question}
            onChange={(e) => setQuestion(e.target.value)}
            maxLength={500}
            rows={3}
            placeholder="ör. Telefon kayıtlarını içeren disklerin nereden alındığı söylendi?"
            autoFocus
          />
        </label>
        <p className="muted small">
          Yanıt yalnızca bu dosyadaki belgelerden, alıntılarla üretilir. Kaynaklar yetersizse sistem yanıt vermez.
          İlgili pasajlar dil modeli sağlayıcısına gönderilir.
        </p>
        <div>
          <button className="btn primary" disabled={busy || !question.trim() || available === false}>
            {busy ? "Yanıt hazırlanıyor… (bir dakika sürebilir)" : "Sor"}
          </button>
        </div>
      </form>
      <ErrorNote message={error} />
      {answer && <AnswerView answer={answer} onReported={refresh} />}

      {history.length > 0 && (
        <section className="card">
          <h2 className="h2">Önceki sorular</h2>
          <ul className="history">
            {history.map((h) => (
              <li key={h.id}>
                <button type="button" className="linkish" onClick={() => open(h.id)}>{h.question}</button>{" "}
                <span className="muted small">
                  {h.outcome === "ANSWERED" ? `${h.claimCount} ifade` : "yanıt verilmedi"} · {h.askedBy.displayName ?? ""} ·{" "}
                  {new Date(h.createdAt).toLocaleString("tr-TR")}
                </span>
              </li>
            ))}
          </ul>
        </section>
      )}
    </>
  );
}

export default function AskPage() {
  const { caseId } = useParams<{ caseId: string }>();
  return (
    <AuthGate>
      <AskView caseId={caseId} />
    </AuthGate>
  );
}
