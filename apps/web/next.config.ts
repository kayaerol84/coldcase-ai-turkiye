import type { NextConfig } from "next";

const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
const oidcAuthority = process.env.NEXT_PUBLIC_OIDC_AUTHORITY ?? "http://localhost:8180/realms/coldcase";
const origin = (u: string) => new URL(u).origin;

// Next.js injects inline bootstrap scripts, so script-src needs 'unsafe-inline' until nonces are wired in;
// everything else is locked to this origin, the API and the identity provider.
const csp = [
  "default-src 'self'",
  "script-src 'self' 'unsafe-inline'",
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' blob: data:",
  "font-src 'self'",
  `connect-src 'self' ${origin(apiUrl)} ${origin(oidcAuthority)}`,
  `frame-src ${origin(oidcAuthority)}`, // silent token renew
  "object-src 'none'",
  "base-uri 'self'",
  "form-action 'self'",
  "frame-ancestors 'none'",
].join("; ");

const nextConfig: NextConfig = {
  reactStrictMode: true,
  poweredByHeader: false,
  // No server-side image optimisation: the app doesn't use next/image, and the optimiser has a history of
  // critical advisories. Page images come from the API as PNGs.
  images: { unoptimized: true },
  async headers() {
    return [
      {
        source: "/:path*",
        headers: [
          { key: "Content-Security-Policy", value: csp },
          { key: "X-Frame-Options", value: "DENY" },
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "Referrer-Policy", value: "no-referrer" },
          { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=(), payment=()" },
          { key: "Cross-Origin-Opener-Policy", value: "same-origin" },
        ],
      },
    ];
  },
};

export default nextConfig;
