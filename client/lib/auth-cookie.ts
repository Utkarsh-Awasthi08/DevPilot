// Shared between hooks/use-auth.ts (client component) and proxy.ts (edge middleware) so the
// cookie name can't drift between the writer and the gate that reads it. Framework-agnostic
// on purpose — proxy.ts runs in the edge runtime and can't pull in client-only React code.
export const AUTH_COOKIE = "devpilot_auth";
