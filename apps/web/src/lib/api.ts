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
  constructor(
    public status: number,
    message: string,
    public code?: string,
    public problem: Record<string, unknown> = {},
  ) {
    super(message);
  }
}

/** Authenticated fetch. JSON bodies are serialised; FormData is sent as multipart. Throws ApiError on non-2xx. */
async function send(method: string, path: string, body?: unknown): Promise<Response> {
  const user = await userManager().getUser();
  if (!user || user.expired) throw new ApiError(401, "Oturum açmanız gerekiyor.");
  const isForm = body instanceof FormData;
  const res = await fetch(`${config.apiUrl}${path}`, {
    method,
    headers: {
      Authorization: `Bearer ${user.access_token}`,
      ...(body !== undefined && !isForm ? { "Content-Type": "application/json" } : {}),
    },
    body: body === undefined ? undefined : isForm ? body : JSON.stringify(body),
  });
  if (!res.ok) {
    let problem: Record<string, unknown> = {};
    try {
      problem = await res.json();
    } catch {
      /* no problem body */
    }
    const code = typeof problem.code === "string" ? problem.code : undefined;
    throw new ApiError(res.status, messageFor(res.status, code), code, problem);
  }
  return res;
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await send(method, path, body);
  return res.status === 204 ? (undefined as T) : ((await res.json()) as T);
}

const codeMessages: Record<string, string> = {
  LAST_CASE_ADMIN: "Bir dosyanın en az bir dosya yöneticisi olmalıdır.",
  USER_NOT_FOUND: "Bu e-posta ile oturum açmış bir kullanıcı yok. Kişinin önce bir kez giriş yapması gerekir.",
  INVALID_PERIOD: "Dönem sonu, dönem başlangıcından önce olamaz.",
  VALIDATION_FAILED: "Girilen bilgiler geçersiz. Lütfen alanları kontrol edin.",
  EMPTY_FILE: "Yüklenen dosya boş.",
  UNSUPPORTED_FILE_TYPE: "Yalnızca PDF dosyaları desteklenir. Dosya içeriği PDF değil.",
  CORRUPT_PDF: "PDF dosyası okunamadı; bozuk olabilir.",
  ENCRYPTED_PDF: "PDF parola korumalı. Parolasız bir kopya yükleyin.",
  EMPTY_PDF: "PDF dosyasında sayfa yok.",
  TOO_MANY_PAGES: "PDF, izin verilen sayfa sayısını aşıyor.",
  FILE_TOO_LARGE: "Dosya, izin verilen en büyük boyutu aşıyor.",
  DUPLICATE_DOCUMENT: "Bu dosya bu çalışma dosyasında zaten var.",
  PROCESSING_IN_PROGRESS: "Belge şu anda işleniyor. İşlem bitince tekrar deneyin.",
  NOT_EXTRACTED: "Sayfalar yeniden işlenmeden önce belgenin metni çıkarılmalı.",
  INVALID_PAGES: "Geçersiz sayfa numarası.",
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

export type DocumentStatus = "PROCESSING" | "READY" | "READY_WITH_WARNINGS" | "FAILED";
export type PageStatus = "PENDING" | "EXTRACTED" | "NEEDS_OCR" | "OCR_DONE" | "FAILED";
type Tone = "neutral" | "positive" | "warning" | "critical" | "accent";

export const documentStatusLabels: Record<DocumentStatus, { tr: string; tone: Tone }> = {
  PROCESSING: { tr: "İşleniyor", tone: "accent" },
  READY: { tr: "Hazır", tone: "positive" },
  READY_WITH_WARNINGS: { tr: "Uyarılarla hazır", tone: "warning" },
  FAILED: { tr: "Başarısız", tone: "critical" },
};

export const pageStatusLabels: Record<PageStatus, { tr: string; tone: Tone }> = {
  PENDING: { tr: "Bekliyor", tone: "neutral" },
  EXTRACTED: { tr: "Metin çıkarıldı", tone: "positive" },
  NEEDS_OCR: { tr: "OCR gerekli", tone: "warning" },
  OCR_DONE: { tr: "OCR yapıldı", tone: "accent" },
  FAILED: { tr: "Başarısız", tone: "critical" },
};

export const statusDetailLabels: Record<string, string> = {
  OCR_UNAVAILABLE: "OCR motoru kurulu değil; taranmış sayfalar okunamadı.",
  OCR_FAILED: "Bazı sayfalarda OCR başarısız oldu.",
  INTEGRITY_CHECK_FAILED: "Bütünlük doğrulaması başarısız: saklanan dosya kayıtlı SHA-256 özetiyle eşleşmiyor.",
  PDF_UNREADABLE: "PDF işlenirken okunamadı.",
  PAGES_NEED_REVIEW: "Bazı sayfalar inceleme gerektiriyor.",
  NO_PAGES: "Belgeden sayfa çıkarılamadı.",
  MAX_ATTEMPTS: "İşlem çok sayıda denemeden sonra durduruldu.",
  VERSION_MISSING: "Belge sürümü bulunamadı.",
  TRANSIENT: "Geçici bir hata nedeniyle işlem başarısız oldu.",
};

export const labelSourceLabels: Record<string, string> = {
  HEADER_FOOTER: "sayfa üst/alt bilgisinden",
  OCR_HEADER_FOOTER: "OCR ile üst/alt bilgiden",
  PDF_PAGE_LABELS: "PDF sayfa etiketinden",
};

export type PageCounts = { total: number; extracted: number; ocrDone: number; needsOcr: number; failed: number; pending: number };

export type DocumentFile = {
  versionId: string;
  sha256: string;
  sizeBytes: number;
  mimeType: string;
  originalFilename?: string;
  pageCount?: number;
  uploadedAt: string;
};

export type Doc = {
  id: string;
  caseId: string;
  title: string;
  sourceName?: string;
  sourceDate?: string;
  language: string;
  status: DocumentStatus;
  statusDetail?: string;
  createdAt: string;
  updatedAt: string;
  file?: DocumentFile;
  pages?: PageCounts;
};

export type Page = {
  id: string;
  pdfPageNumber: number;
  printedPageLabel?: string;
  printedLabelSource?: string;
  status: PageStatus;
  method?: "EMBEDDED_TEXT" | "OCR";
  quality?: number;
  ocrConfidence?: number;
  error?: string;
  embeddedText?: string;
  ocrText?: string;
  ocrEngine?: string;
  ocrEngineVersion?: string;
  extractorVersion?: string;
};

export type UploadInput = { file: File; title?: string; sourceName?: string; sourceDate?: string };

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

  listDocuments: (caseId: string) => request<Doc[]>("GET", `/api/v1/cases/${caseId}/documents`),
  uploadDocument: (caseId: string, input: UploadInput) => {
    const form = new FormData();
    form.append("file", input.file);
    if (input.title) form.append("title", input.title);
    if (input.sourceName) form.append("sourceName", input.sourceName);
    if (input.sourceDate) form.append("sourceDate", input.sourceDate);
    return request<Doc>("POST", `/api/v1/cases/${caseId}/documents`, form);
  },
  getDocument: (id: string) => request<Doc>("GET", `/api/v1/documents/${id}`),
  listPages: (id: string) => request<Page[]>("GET", `/api/v1/documents/${id}/pages`),
  getPage: (id: string, n: number) => request<Page>("GET", `/api/v1/documents/${id}/pages/${n}`),
  reprocess: (id: string, pages?: number[]) =>
    request<Doc>("POST", `/api/v1/documents/${id}/reprocess`, pages ? { pages } : undefined),
  /** Fetches the original with the bearer token and returns a blob URL (the API is not cookie-authenticated). */
  originalFileUrl: async (id: string, inline: boolean) => {
    const res = await send("GET", `/api/v1/documents/${id}/file?disposition=${inline ? "inline" : "attachment"}`);
    return URL.createObjectURL(await res.blob());
  },
};

export function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
}
