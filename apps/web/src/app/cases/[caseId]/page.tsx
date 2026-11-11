"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { AuthGate } from "@/components/AuthGate";
import { DocumentsSection } from "@/components/DocumentsSection";
import { ErrorNote } from "@/components/ErrorNote";
import { api, Case, CaseRole, Member, roleLabels } from "@/lib/api";

const roles = Object.keys(roleLabels) as CaseRole[];

function CaseDetail({ caseId }: { caseId: string }) {
  const [c, setCase] = useState<Case | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState(false);

  useEffect(() => {
    api.getCase(caseId).then(setCase).catch((e: Error) => setError(e.message));
  }, [caseId]);

  if (error) return <><BackLink /><ErrorNote message={error} /></>;
  if (!c) return <p className="muted" role="status">Yükleniyor…</p>;

  // UI hints only; the API enforces permissions on every request.
  const isAdmin = c.myRole === "CASE_ADMIN";

  return (
    <>
      <BackLink />
      {editing ? (
        <EditCaseForm c={c} onDone={(updated) => { if (updated) setCase(updated); setEditing(false); }} />
      ) : (
        <section className="card">
          <div className="row between">
            <h1 className="h1">{c.title}</h1>
            {isAdmin && <button className="btn" onClick={() => setEditing(true)}>Düzenle</button>}
          </div>
          {c.description && <p className="pre">{c.description}</p>}
          <dl className="meta">
            <dt>Dönem</dt>
            <dd>{c.periodStart || c.periodEnd ? `${c.periodStart ?? "?"} – ${c.periodEnd ?? "?"}` : "Belirtilmemiş"}</dd>
            <dt>Dil</dt>
            <dd>{c.primaryLanguage}</dd>
            <dt>Rolünüz</dt>
            <dd>{roleLabels[c.myRole]}</dd>
          </dl>
        </section>
      )}
      <DocumentsSection caseId={caseId} canUpload={isAdmin} />
      <Members caseId={caseId} canManage={isAdmin} />
    </>
  );
}

function BackLink() {
  return <p><Link href="/">← Dosyalar</Link></p>;
}

function EditCaseForm({ c, onDone }: { c: Case; onDone: (updated?: Case) => void }) {
  const [title, setTitle] = useState(c.title);
  const [description, setDescription] = useState(c.description ?? "");
  const [periodStart, setPeriodStart] = useState(c.periodStart ?? "");
  const [periodEnd, setPeriodEnd] = useState(c.periodEnd ?? "");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      onDone(await api.updateCase(c.id, {
        title,
        description,
        periodStart: periodStart || undefined,
        periodEnd: periodEnd || undefined,
      }));
    } catch (err) {
      setError((err as Error).message);
      setBusy(false);
    }
  }

  return (
    <form className="card form" onSubmit={submit}>
      <label>
        Başlık
        <input required maxLength={300} value={title} onChange={(e) => setTitle(e.target.value)} />
      </label>
      <label>
        Açıklama
        <textarea rows={4} maxLength={10000} value={description} onChange={(e) => setDescription(e.target.value)} />
      </label>
      <div className="row">
        <label>
          Dönem başlangıcı
          <input type="date" value={periodStart} onChange={(e) => setPeriodStart(e.target.value)} />
        </label>
        <label>
          Dönem sonu
          <input type="date" value={periodEnd} onChange={(e) => setPeriodEnd(e.target.value)} />
        </label>
      </div>
      <ErrorNote message={error} />
      <div className="row">
        <button className="btn primary" disabled={busy}>Kaydet</button>
        <button type="button" className="btn" onClick={() => onDone()}>Vazgeç</button>
      </div>
    </form>
  );
}

function Members({ caseId, canManage }: { caseId: string; canManage: boolean }) {
  const [members, setMembers] = useState<Member[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [email, setEmail] = useState("");
  const [role, setRole] = useState<CaseRole>("RESEARCHER");

  const load = useCallback(() => {
    api.listMembers(caseId).then(setMembers).catch((e: Error) => setError(e.message));
  }, [caseId]);
  useEffect(load, [load]);

  async function run(action: () => Promise<unknown>) {
    setError(null);
    try {
      await action();
      load();
    } catch (err) {
      setError((err as Error).message);
    }
  }

  return (
    <section className="card">
      <h2 className="h2">Üyeler</h2>
      <ErrorNote message={error} />
      <table className="table">
        <thead>
          <tr><th scope="col">Kişi</th><th scope="col">Rol</th>{canManage && <th scope="col"><span className="sr-only">İşlemler</span></th>}</tr>
        </thead>
        <tbody>
          {members?.map((m) => (
            <tr key={m.userId}>
              <td>{m.displayName ?? m.email}<div className="muted small">{m.email}</div></td>
              <td>
                {canManage && m.email ? (
                  <select
                    aria-label={`${m.email} rolü`}
                    value={m.role}
                    onChange={(e) => run(() => api.setMember(caseId, m.email!, e.target.value as CaseRole))}
                  >
                    {roles.map((r) => <option key={r} value={r}>{roleLabels[r]}</option>)}
                  </select>
                ) : (
                  roleLabels[m.role]
                )}
              </td>
              {canManage && (
                <td>
                  <button className="btn danger" onClick={() => run(() => api.removeMember(caseId, m.userId))}>Çıkar</button>
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
      {canManage && (
        <form
          className="row add-member"
          onSubmit={(e) => {
            e.preventDefault();
            run(() => api.setMember(caseId, email, role)).then(() => setEmail(""));
          }}
        >
          <label>
            E-posta
            <input type="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
          </label>
          <label>
            Rol
            <select value={role} onChange={(e) => setRole(e.target.value as CaseRole)}>
              {roles.map((r) => <option key={r} value={r}>{roleLabels[r]}</option>)}
            </select>
          </label>
          <button className="btn primary">Ekle</button>
        </form>
      )}
      {canManage && <p className="muted small">Kişinin en az bir kez oturum açmış olması gerekir.</p>}
    </section>
  );
}

export default function CasePage() {
  const { caseId } = useParams<{ caseId: string }>();
  return (
    <AuthGate>
      <CaseDetail caseId={caseId} />
    </AuthGate>
  );
}
