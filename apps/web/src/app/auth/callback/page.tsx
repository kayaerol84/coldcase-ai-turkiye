"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { userManager } from "@/lib/auth";
import { ErrorNote } from "@/components/ErrorNote";

export default function AuthCallback() {
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);
  const handled = useRef(false);

  useEffect(() => {
    if (handled.current) return; // strict mode runs effects twice; the code can only be exchanged once
    handled.current = true;
    userManager()
      .signinRedirectCallback()
      .then((user) => {
        const target = typeof user.state === "string" && user.state.startsWith("/") ? user.state : "/";
        router.replace(target);
      })
      .catch(() => setError("Oturum açma tamamlanamadı. Lütfen tekrar deneyin."));
  }, [router]);

  return error ? <ErrorNote message={error} /> : <p className="muted" role="status">Oturum açılıyor…</p>;
}
