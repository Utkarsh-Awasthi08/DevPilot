import { getApiBaseUrl, ApiError, type ChatMessage } from "@/lib/api";

export type StreamChatHandlers = {
  onUserMessage?: (message: ChatMessage) => void;
  onToken?: (token: string) => void;
  onAssistantMessage?: (message: ChatMessage) => void;
  onDone?: () => void;
  onError?: (error: Error) => void;
  signal?: AbortSignal;
};

/**
 * The backend sends a plain-text (JSON-encoded string) safe message on `event: error` — see
 * ChatStreamHandler.sendErrorAndComplete. Parsed defensively anyway rather than trusting that
 * discipline blindly: if it's JSON with a message/error field, use that; otherwise fall back to
 * the raw text only if it looks like plausible short human copy, never anything unbounded.
 */
function parseSseErrorMessage(data: string): string {
  try {
    const parsed = JSON.parse(data);
    if (typeof parsed === "string") return parsed;
    if (parsed && typeof parsed.message === "string") return parsed.message;
    if (parsed && typeof parsed.error === "string") return parsed.error;
  } catch {
    // Not JSON — fall through to the raw-text fallback below.
  }
  return data.length > 0 && data.length < 300
    ? data
    : "The assistant is temporarily unavailable. Please try again.";
}

export async function streamChatMessage(
  sessionId: string,
  content: string,
  handlers: StreamChatHandlers = {}
): Promise<void> {
  let res: Response;
  try {
    res = await fetch(
      `${getApiBaseUrl()}/api/chat/sessions/${sessionId}/messages`,
      {
        method: "POST",
        credentials: "include",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ content }),
        signal: handlers.signal,
      }
    );
  } catch (err) {
    if (err instanceof DOMException && err.name === "AbortError") throw err;
    throw new ApiError(0, "Unable to reach the server. Check your connection and try again.");
  }

  if (!res.ok) {
    let message = res.statusText;
    try {
      const data = await res.json();
      message = data.message ?? data.error ?? message;
    } catch {
      // ignore
    }
    throw new ApiError(res.status, message);
  }

  if (!res.body) {
    throw new Error("No response body for SSE stream");
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;

    buffer += decoder.decode(value, { stream: true });
    const parts = buffer.split("\n\n");
    buffer = parts.pop() ?? "";

    for (const part of parts) {
      if (!part.trim()) continue;

      const lines = part.split("\n");
      let event = "message";
      const dataLines: string[] = [];

      for (const line of lines) {
        if (line.startsWith("event:")) {
          event = line.slice(6).trim();
        } else if (line.startsWith("data:")) {
          dataLines.push(line.slice(5).trimStart());
        }
      }

      const data = dataLines.join("\n");
      if (!data) continue;

      try {
        if (event === "token") {
          handlers.onToken?.(JSON.parse(data) as string);
        } else if (event === "user_message") {
          handlers.onUserMessage?.(JSON.parse(data) as ChatMessage);
        } else if (event === "assistant_message") {
          handlers.onAssistantMessage?.(JSON.parse(data) as ChatMessage);
        } else if (event === "error") {
          handlers.onError?.(new Error(parseSseErrorMessage(data)));
        }
        // "done" is a no-op here — onDone fires exactly once, after the read loop below ends,
        // regardless of whether the server sent an explicit done event.
      } catch {
        // A malformed token/user_message/assistant_message payload — never surface the raw
        // parser error (e.g. "Unexpected token h in JSON at position 0") to the user.
        handlers.onError?.(new Error("Received an invalid response from the server."));
      }
    }
  }

  handlers.onDone?.();
}