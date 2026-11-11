"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { AuthGate } from "@/components/AuthGate";
import { ErrorNote } from "@/components/ErrorNote";
import { api, Case, roleLabels } from "@/lib/api";

function CaseList() {
  const [cases, setCases] = useState<Case[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);

  const load = useCallback(() => {
    api.listCases().then(setCases).catch((e: Error) => setError(e.message));
  }, []);
  useEffect(load, [load]);

  return (
    <>
      <div className="row between">
        <h1 className="h1">Dosyalar</h1>
        <button className="btn primary" onClick={() => setShowForm((v) => !v)} aria-expanded={showForm}>
          {showForm ? "Vazgeç" : "Yeni dosya"}
        </button>
      </div>
      {showForm && (
        <CreateCaseForm
          onCreated={() => {
            setShowForm(false);
            load();
          }}
        />
      )}
      <ErrorNote message={error} />
      {cases === null && !error && <p className="muted" role="status">Yükleniyor…</p>}
      {cases?.length === 0 && (
        <p className="card muted">Henüz erişebildiğiniz bir dosya yok. Yeni bir dosya oluşturun veya bir dosya yöneticisinden davet isteyin.</p>
      )}
      <div className="grid">
        {cases?.map((c) => (
          <Link key={c.id} href={`/cases/${c.id}`} className="card case-card">
            <h2 className="h2">{c.title}</h2>
            {c.description && <p className="muted clamp">{c.description}</p>}
            <span className="label" data-tone="accent">Rolünüz: {roleLabels[c.myRole]}</span>
          </Link>
        ))}
      </div>
    </>
  );
}

function CreateCaseForm({ onCreated }: { onCreated: () => void }) {
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [periodStart, setPeriodStart] = useState("");
  const [periodEnd, setPeriodEnd] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.createCase({
        title,
        description: description || undefined,
        periodStart: periodStart || undefined,
        periodEnd: periodEnd || undefined,
      });
      onCreated();
    } catch (err) {
      setError((err as Error).message);
    } finally {
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
        <textarea rows={3} maxLength={10000} value={description} onChange={(e) => setDescription(e.target.value)} />
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
      <button className="btn primary" disabled={busy}>{busy ? "Oluşturuluyor…" : "Dosyayı oluştur"}</button>
    </form>
  );
}

export default function Home() {
  return (
    <AuthGate>
      <CaseList />
    </AuthGate>
  );
}
