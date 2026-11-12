// Verifies security headers on a running web app: node scripts/check-headers.mjs [baseUrl]
const base = process.argv[2] ?? "http://localhost:3000";
const required = {
  "content-security-policy": (v) => v.includes("frame-ancestors 'none'") && v.includes("object-src 'none'") && !v.includes("unsafe-eval"),
  "x-frame-options": (v) => v === "DENY",
  "x-content-type-options": (v) => v === "nosniff",
  "referrer-policy": (v) => v === "no-referrer",
  "permissions-policy": (v) => v.includes("camera=()"),
};
const forbidden = ["x-powered-by"];

let failed = false;
for (const path of ["/", "/cases/00000000-0000-0000-0000-000000000000", "/auth/callback"]) {
  const res = await fetch(base + path, { redirect: "manual" });
  for (const [name, ok] of Object.entries(required)) {
    const v = res.headers.get(name);
    if (!v || !ok(v)) {
      console.error(`${path}: header ${name} missing or wrong: ${v}`);
      failed = true;
    }
  }
  for (const name of forbidden) {
    if (res.headers.has(name)) {
      console.error(`${path}: header ${name} must not be sent`);
      failed = true;
    }
  }
}
if (failed) process.exit(1);
console.log("Security headers OK");
