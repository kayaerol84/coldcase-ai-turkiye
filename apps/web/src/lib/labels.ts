// Information classification labels (BR §9). Status is always conveyed with text, never colour alone.
export const findingLabels = {
  SOURCE_STATEMENT: { tr: "Kaynak beyanı", tone: "neutral" },
  DOCUMENTED_FACT: { tr: "Belgelenmiş olgu", tone: "neutral" },
  CORROBORATED: { tr: "Teyit edilmiş", tone: "positive" },
  DISPUTED: { tr: "Tartışmalı", tone: "warning" },
  UNVERIFIED_ALLEGATION: { tr: "Doğrulanmamış iddia", tone: "warning" },
  RESEARCH_LEAD: { tr: "Araştırma ipucu", tone: "accent" },
  MISSING_FROM_CORPUS: { tr: "Derlemde yok", tone: "neutral" },
  REQUESTED_NOT_RECEIVED: { tr: "Resmen istendi, ulaşmadı", tone: "critical" },
  INCONCLUSIVE: { tr: "Sonuçsuz", tone: "neutral" },
} as const;

export type FindingLabel = keyof typeof findingLabels;
