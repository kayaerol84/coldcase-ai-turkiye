type Range = { start: number; end: number };

/** Text with <mark>ed ranges, offset by `base` (ranges are relative to the full text). */
function Marked({ text, base, ranges }: { text: string; base: number; ranges: Range[] }) {
  const parts: React.ReactNode[] = [];
  let pos = 0;
  const local = ranges
    .map((r) => ({ start: Math.max(0, r.start - base), end: Math.min(text.length, r.end - base) }))
    .filter((r) => r.end > r.start)
    .sort((a, b) => a.start - b.start);
  for (const r of local) {
    if (r.start < pos) continue;
    if (r.start > pos) parts.push(text.slice(pos, r.start));
    parts.push(<mark key={base + r.start}>{text.slice(r.start, r.end)}</mark>);
    pos = r.end;
  }
  if (pos < text.length) parts.push(text.slice(pos));
  return <>{parts}</>;
}

/**
 * Renders source text with highlighted ranges. Text is always rendered as text (never HTML),
 * so document content cannot inject markup. `focus` marks the cited passage as one region.
 */
export function HighlightedText({ text, ranges, focus }: { text: string; ranges: Range[]; focus?: Range }) {
  if (!focus || focus.start < 0 || focus.end > text.length || focus.end <= focus.start) {
    return <Marked text={text} base={0} ranges={ranges} />;
  }
  return (
    <>
      <Marked text={text.slice(0, focus.start)} base={0} ranges={ranges} />
      <span className="focus">
        <Marked text={text.slice(focus.start, focus.end)} base={focus.start} ranges={ranges} />
      </span>
      <Marked text={text.slice(focus.end)} base={focus.end} ranges={ranges} />
    </>
  );
}
