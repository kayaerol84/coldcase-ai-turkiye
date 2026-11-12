// Mirrors the API's TurkishText: one folded char per input char, so offsets line up.
const FROM = "İIıÂâÎîÛûÇçĞğÖöŞşÜü";
const TO = "iiiaaiiuuccggoossuu";

export function fold(s: string): string {
  let out = "";
  for (const c of s) {
    const k = FROM.indexOf(c);
    out += k >= 0 ? TO[k] : c.toLowerCase().length === 1 ? c.toLowerCase() : c;
  }
  return out;
}

const STOPWORDS = new Set(
  "ve ile bir bu su o ne mi mu da de ki icin gibi daha cok hakkinda olan olarak ama veya ya hangi nasil neden niye kim kimin nerede zaman her hic sey ise en var yok midir nedir dair".split(" "),
);

/** Highlight ranges for query terms (prefix match handles Turkish suffixes) and quoted phrases. */
export function termHighlights(text: string, query: string): { start: number; end: number }[] {
  const folded = fold(text);
  const phrases = [...query.matchAll(/"([^"]{2,200})"/g)].map((m) => fold(m[1]).trim());
  const terms = [...fold(query.replace(/"[^"]*"/g, " ")).matchAll(/[\p{L}\p{N}]+/gu)]
    .map((m) => m[0])
    .filter((t) => t.length >= 2 && !STOPWORDS.has(t));
  const ranges: { start: number; end: number }[] = [];
  for (const m of folded.matchAll(/[\p{L}\p{N}]+/gu)) {
    const w = m[0];
    if (terms.some((t) => w.startsWith(t.slice(0, Math.max(4, t.length - 3))) && w.length >= Math.min(t.length, 4))) {
      ranges.push({ start: m.index!, end: m.index! + w.length });
    }
  }
  for (const p of phrases) {
    let i = folded.indexOf(p);
    while (i >= 0 && p) {
      ranges.push({ start: i, end: i + p.length });
      i = folded.indexOf(p, i + 1);
    }
  }
  return ranges;
}
