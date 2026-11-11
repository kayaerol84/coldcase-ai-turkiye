"use client";

import { config } from "./config";
import { userManager } from "./auth";

export type CaseRole = "VIEWER" | "RESEARCHER" | "REVIEWER" | "CASE_ADMIN";

export const roleLabels: Record<CaseRole, string> = {
  VIEWER: "Görüntüleyici",
  RESEARCHER: "Araştırmacı",
  REVIEWER: "İnceleyici",
  CASE_ADMIN: "Dosya yöneticisi",
};

export type Case = {
  id: string;
  title: string;
  description?: string;
  primaryLanguage: string;
  periodStart?: string;
  periodEnd?: string;
  createdAt: string;
  updatedAt: string;
  myRole: CaseRole;
};

export type Member = {
  userId: string;
  email?: string;
  displayName?: string;
  role: CaseRole;
  grantedAt: string;
};

export type Me = { id: string; email?: string; displayName?: string; platformAdmin: boolean };

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const user = await userManager().getUser();
  if (!user || user.expired) throw new ApiError(401, "Oturum açmanız gerekiyor.");
  const res = await fetch(`${config.apiUrl}${path}`, {
    method,
    headers: {
      Authorization: `Bearer ${user.access_token}`,
      ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) {
    let code: string | undefined;
    try {
      code = (await res.json()).code;
    } catch {
      /* no problem body */
    }
    throw new ApiError(res.status, messageFor(res.status, code));
  }
  return res.status === 204 ? (undefined as T) : ((await res.json()) as T);
}

const codeMessages: Record<string, string> = {
  LAST_CASE_ADMIN: "Bir dosyanın en az bir dosya yöneticisi olmalıdır.",
  USER_NOT_FOUND: "Bu e-posta ile oturum açmış bir kullanıcı yok. Kişinin önce bir kez giriş yapması gerekir.",
  INVALID_PERIOD: "Dönem sonu, dönem başlangıcından önce olamaz.",
  VALIDATION_FAILED: "Girilen bilgiler geçersiz. Lütfen alanları kontrol edin.",
};

function messageFor(status: number, code?: string): string {
  if (code && codeMessages[code]) return codeMessages[code];
  switch (status) {
    case 401:
      return "Oturumunuzun süresi doldu. Lütfen yeniden giriş yapın.";
    case 403:
      return "Bu işlem için yetkiniz yok.";
    case 404:
      return "Kayıt bulunamadı veya erişim yetkiniz yok.";
    default:
      return `Beklenmeyen bir hata oluştu (${status}).`;
  }
}

export const api = {
  me: () => request<Me>("GET", "/api/v1/me"),
  listCases: () => request<Case[]>("GET", "/api/v1/cases"),
  getCase: (id: string) => request<Case>("GET", `/api/v1/cases/${id}`),
  createCase: (body: Partial<Case> & { title: string }) => request<Case>("POST", "/api/v1/cases", body),
  updateCase: (id: string, body: Partial<Case>) => request<Case>("PATCH", `/api/v1/cases/${id}`, body),
  listMembers: (id: string) => request<Member[]>("GET", `/api/v1/cases/${id}/members`),
  setMember: (id: string, email: string, role: CaseRole) =>
    request<Member>("PUT", `/api/v1/cases/${id}/members`, { email, role }),
  removeMember: (id: string, userId: string) => request<void>("DELETE", `/api/v1/cases/${id}/members/${userId}`),
};
