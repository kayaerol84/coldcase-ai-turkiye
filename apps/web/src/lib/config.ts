export const config = {
  apiUrl: process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080",
  oidcAuthority: process.env.NEXT_PUBLIC_OIDC_AUTHORITY ?? "http://localhost:8180/realms/coldcase",
  oidcClientId: process.env.NEXT_PUBLIC_OIDC_CLIENT_ID ?? "coldcase-web",
};
