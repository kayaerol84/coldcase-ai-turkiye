"use client";

import { User, UserManager, WebStorageStateStore } from "oidc-client-ts";
import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import { config } from "./config";

let manager: UserManager | null = null;

/** Lazily created so it only runs in the browser. Authorization Code + PKCE, public client. */
export function userManager(): UserManager {
  if (!manager) {
    manager = new UserManager({
      authority: config.oidcAuthority,
      client_id: config.oidcClientId,
      redirect_uri: `${window.location.origin}/auth/callback`,
      post_logout_redirect_uri: window.location.origin,
      response_type: "code",
      scope: "openid profile email",
      // Session-scoped storage: tokens do not survive closing the tab.
      userStore: new WebStorageStateStore({ store: window.sessionStorage }),
      automaticSilentRenew: true,
    });
  }
  return manager;
}

type AuthState = {
  user: User | null;
  loading: boolean;
  signIn: () => Promise<void>;
  signOut: () => Promise<void>;
};

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const um = userManager();
    um.getUser()
      .then((u) => setUser(u && !u.expired ? u : null))
      .finally(() => setLoading(false));
    const onLoaded = (u: User) => setUser(u);
    const onUnloaded = () => setUser(null);
    um.events.addUserLoaded(onLoaded);
    um.events.addUserUnloaded(onUnloaded);
    um.events.addSilentRenewError(onUnloaded);
    return () => {
      um.events.removeUserLoaded(onLoaded);
      um.events.removeUserUnloaded(onUnloaded);
      um.events.removeSilentRenewError(onUnloaded);
    };
  }, []);

  const signIn = useCallback(() => userManager().signinRedirect({ state: window.location.pathname }), []);
  const signOut = useCallback(() => userManager().signoutRedirect(), []);

  const value = useMemo(() => ({ user, loading, signIn, signOut }), [user, loading, signIn, signOut]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used inside AuthProvider");
  return ctx;
}
