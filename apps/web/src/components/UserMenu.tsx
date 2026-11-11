"use client";

import { useAuth } from "@/lib/auth";

export function UserMenu() {
  const { user, signOut } = useAuth();
  if (!user) return null;
  const name = user.profile.name ?? user.profile.preferred_username ?? user.profile.email;
  return (
    <div className="usermenu">
      <span className="muted">{name}</span>
      <button className="btn" onClick={() => signOut()}>Çıkış</button>
    </div>
  );
}
