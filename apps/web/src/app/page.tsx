import { findingLabels } from "@/lib/labels";

export default function Home() {
  return (
    <>
      <section className="card">
        <h1 style={{ marginTop: 0 }}>Dosyalar</h1>
        <p className="muted">
          Erişim yetkiniz olan dosyalar burada listelenecek. Oturum açma (OIDC) ve dosya listesi T0 kapsamında
          bağlanacaktır.
        </p>
        <p className="muted" style={{ fontSize: 14 }}>
          Bu sistem bir araştırma yardımcısıdır: yapay zekâ çıktıları taslaktır, her bulgu kaynağa ve sayfaya
          bağlıdır ve insan incelemesi gerektirir.
        </p>
      </section>

      <section className="card">
        <h2 style={{ marginTop: 0 }}>Bilgi sınıflandırma etiketleri</h2>
        <div>
          {Object.entries(findingLabels).map(([key, l]) => (
            <span key={key} className="label" data-tone={l.tone}>
              {l.tr}
            </span>
          ))}
        </div>
      </section>
    </>
  );
}
