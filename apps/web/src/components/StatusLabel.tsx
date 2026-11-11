/** Status is always shown as text; colour only reinforces it (TR §11.3). */
export function StatusLabel({ text, tone }: { text: string; tone: string }) {
  return (
    <span className="label" data-tone={tone}>
      {text}
    </span>
  );
}
