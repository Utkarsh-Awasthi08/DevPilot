"use client";

import { useEffect } from "react";

// This replaces the ENTIRE root layout (including globals.css's provider tree) when something
// fails at that level, so it can't rely on Tailwind/the app's design system being available —
// plain inline styles only, as a last-resort safety net.
export default function GlobalError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    console.error(error);
  }, [error]);

  return (
    <html lang="en">
      <body>
        <div
          style={{
            display: "flex",
            minHeight: "100vh",
            flexDirection: "column",
            alignItems: "center",
            justifyContent: "center",
            gap: "1rem",
            padding: "1rem",
            textAlign: "center",
            fontFamily: "system-ui, sans-serif",
          }}
        >
          <h1 style={{ fontSize: "1.25rem", fontWeight: 600 }}>Something went wrong</h1>
          <p style={{ maxWidth: "24rem", fontSize: "0.875rem", color: "#666" }}>
            The application failed to load. Please try refreshing the page.
          </p>
          <button
            onClick={() => reset()}
            style={{
              padding: "0.5rem 1rem",
              borderRadius: "0.5rem",
              border: "1px solid #ccc",
              cursor: "pointer",
              background: "none",
            }}
          >
            Try again
          </button>
        </div>
      </body>
    </html>
  );
}
