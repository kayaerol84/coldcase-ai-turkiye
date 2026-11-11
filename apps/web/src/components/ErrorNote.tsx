export function ErrorNote({ message }: { message: string | null }) {
  if (!message) return null;
  return (
    <p className="error" role="alert">
      <strong>Hata:</strong> {message}
    </p>
  );
}
