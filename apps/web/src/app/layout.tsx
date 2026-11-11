import type { Metadata } from "next";
import Link from "next/link";
import { Providers } from "@/components/Providers";
import { UserMenu } from "@/components/UserMenu";
import "./globals.css";

export const metadata: Metadata = {
  title: "ColdCase AI Türkiye",
  description: "Kaynağa dayalı araştırma çalışma alanı",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="tr">
      <body>
        <Providers>
          <div className="shell">
            <header className="topbar">
              <Link href="/" className="brand">ColdCase AI Türkiye</Link>
              <span className="muted tagline">Kaynağa dayalı araştırma çalışma alanı</span>
              <UserMenu />
            </header>
            <main>{children}</main>
            <footer className="muted footer">
              Yapay zekâ çıktıları taslaktır; her bulgu kaynağa bağlıdır ve insan incelemesi gerektirir.
            </footer>
          </div>
        </Providers>
      </body>
    </html>
  );
}
