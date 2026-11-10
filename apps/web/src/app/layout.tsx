import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "ColdCase AI Türkiye",
  description: "Kaynağa dayalı araştırma çalışma alanı",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="tr">
      <body>
        <div className="shell">
          <header className="topbar">
            <span className="brand">ColdCase AI Türkiye</span>
            <span className="muted">Kaynağa dayalı araştırma çalışma alanı</span>
          </header>
          <main>{children}</main>
        </div>
      </body>
    </html>
  );
}
