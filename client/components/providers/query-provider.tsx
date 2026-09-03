"use client";

import React from "react";
import {
  MutationCache,
  QueryCache,
  QueryClient,
  QueryClientProvider,
} from "@tanstack/react-query";

import { toast } from "@/components/ui/toast";
import { setAuthCookie } from "@/hooks/use-auth";
import { ApiError } from "@/lib/api";

function isUnauthorized(error: unknown) {
  return error instanceof ApiError && error.status === 401;
}

function shouldRetry(failureCount: number, error: unknown) {
  // Client errors (bad auth, not found, bad request, validation) won't resolve on their own —
  // retrying them just delays the error the user eventually sees. Only transient/server-side
  // failures get a couple of retries.
  if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
    return false;
  }
  return failureCount < 2;
}

function redirectToLogin() {
  setAuthCookie(false);
  // QueryCache/MutationCache callbacks run outside React's tree, so useRouter() isn't
  // available here — and a full navigation is actually what we want on a forced logout, since
  // it resets all in-memory app/query state rather than leaving stale data behind.
  if (typeof window !== "undefined" && !window.location.pathname.startsWith("/login")) {
    // eslint-disable-next-line @next/next/no-location-assign-relative-destination
    window.location.assign("/login?error=session");
  }
}

const QueryProvider = ({ children }: { children: React.ReactNode }) => {
  const [query] = React.useState(
    () =>
      new QueryClient({
        defaultOptions: {
          // React Query's default (true) meant switching back to this tab silently re-ran
          // every active query, including the repos list — which forces a GitHub resync
          // whenever the cached list happens to be empty. Queries that specifically want
          // focus-refetch (e.g. auth) can still opt back in per-query.
          queries: { refetchOnWindowFocus: false, retry: shouldRetry },
        },
        // A session that expires mid-use previously left the user stuck on a broken page —
        // only the auth/me query's own failure ever redirected. This makes it universal:
        // ANY query or mutation hitting a 401 sends the user back to /login.
        queryCache: new QueryCache({
          onError: (error) => {
            if (isUnauthorized(error)) redirectToLogin();
          },
        }),
        // Mutations are fire-and-forget by nature, so a failure with no onError previously
        // showed nothing at all. This is a safety net only — a mutation with its own onError
        // (most of them) is never double-notified.
        mutationCache: new MutationCache({
          onError: (error, _variables, _context, mutation) => {
            if (isUnauthorized(error)) {
              redirectToLogin();
              return;
            }
            if (!mutation.options.onError) {
              toast.add({
                title: "Something went wrong",
                description: error instanceof Error ? error.message : "Please try again.",
                type: "error",
              });
            }
          },
        }),
      })
  );
  return <QueryClientProvider client={query}>{children}</QueryClientProvider>;
};
export default QueryProvider;
