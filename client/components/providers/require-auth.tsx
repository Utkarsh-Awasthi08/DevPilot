"use client";

import { usePathname, useRouter } from "next/navigation";
import { useEffect } from "react";

import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { useCurrentUser } from "@/hooks/use-auth";
import { ApiError } from "@/lib/api";

export function RequireAuth({ children }: { children: React.ReactNode }) {
  const { data: user, isLoading, isError, error, refetch } = useCurrentUser();
  const router = useRouter();
  const pathname = usePathname();

  // A network blip or backend outage previously looked identical to "you were never logged
  // in" — both just redirected to /login with no explanation. Only a real 401 means that;
  // anything else gets a retry option instead of an unexplained bounce.
  const isTransientError = isError && !(error instanceof ApiError && error.status === 401);

  useEffect(() => {
    if (!isLoading && !user && !isTransientError) {
      router.replace(`/login?next=${encodeURIComponent(pathname)}`);
    }
  }, [isLoading, user, isTransientError, router, pathname]);

  if (isTransientError) {
    return (
      <div className="flex min-h-svh items-center justify-center px-4">
        <div className="flex max-w-sm flex-col items-center gap-3 text-center text-muted-foreground">
          <p className="text-sm">Couldn&apos;t verify your session: {error.message}</p>
          <Button size="sm" variant="outline" onClick={() => void refetch()}>
            Try again
          </Button>
        </div>
      </div>
    );
  }

  // Keep showing the spinner (rather than flashing to a blank page) through isLoading AND the
  // brief window before the redirect effect above actually navigates away.
  if (isLoading || !user) {
    return (
      <div className="flex min-h-svh items-center justify-center">
        <div className="flex flex-col items-center gap-3 text-muted-foreground">
          <Spinner className="size-6" />
          <p className="text-sm">Loading your workspace…</p>
        </div>
      </div>
    );
  }

  return <>{children}</>;
}
