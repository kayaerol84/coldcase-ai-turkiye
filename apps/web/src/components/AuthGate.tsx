"use client";

import { useAuth } from "@/lib/auth";

/** Renders children only for signed-in users. Server-side authorisation is still enforced on every API call. */
export function AuthGate({ children }: { children: React.ReactNode }) {
  const { user, loading, signIn } = useAuth();
  if (loading) return <p className="muted" role="status">Yükleniyor…</p>;
  if (!user) {
    return (
      <section className="card">
        <h1 className="h1">Giriş yapın</h1>
        <p className="muted">Dosyalara erişmek için kurumsal hesabınızla oturum açın.</p>
        <button className="btn primary" onClick={() => signIn()}>Giriş yap</button>
      </section>
    );
  }
  return <>{children}</>;
}
