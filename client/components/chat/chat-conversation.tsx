"use client";

import { ChatComposer } from "@/components/chat/chat-composer";
import { ChatMessages } from "@/components/chat/chat-messages";
import { useStreamChat } from "@/hooks/use-chat";
import type { ChatMessage, Repository } from "@/lib/api";

/**
 * Render this keyed by sessionId (see ChatView) — useStreamChat's internal streaming/streamText
 * state is scoped to whichever session was active when the hook instance was created, so a
 * session switch needs a fresh instance rather than an in-place reset.
 */
export function ChatConversation({
  sessionId,
  repo,
  messages,
  isLoading,
  isError,
  errorMessage,
  onRetry,
}: {
  sessionId: string | null;
  repo: Repository;
  messages: ChatMessage[];
  isLoading?: boolean;
  isError?: boolean;
  errorMessage?: string;
  onRetry?: () => void;
}) {
  const { send, stop, streaming, streamText } = useStreamChat(sessionId);

  return (
    <>
      <ChatMessages
        repo={repo}
        messages={messages}
        streamText={streamText}
        isLoading={isLoading}
        isError={isError}
        errorMessage={errorMessage}
        onRetry={onRetry}
      />
      <ChatComposer
        disabled={!sessionId}
        streaming={streaming}
        onSend={send}
        onStop={stop}
      />
    </>
  );
}
