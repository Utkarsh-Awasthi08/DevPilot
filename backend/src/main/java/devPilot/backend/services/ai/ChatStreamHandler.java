package devPilot.backend.services.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import devPilot.backend.dto.ChatMessageResponse;
import devPilot.backend.dto.CitationDto;
import devPilot.backend.entity.ChatMessage;
import devPilot.backend.entity.MessageRole;
import devPilot.backend.repository.ChatMessageRepository;
import devPilot.backend.services.ratelimit.TokenBucketRateLimiter;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * Generation step: call OpenAI-compatible Groq via Spring AI and stream tokens to the browser
 * over SSE.
 */
@Component
@Slf4j
public class ChatStreamHandler {

    private final ChatModel chatModel;
    private final ChatMessageRepository chatMessageRepository;
    private final CitationMapper citationMapper;
    private final TokenCountEstimator tokenCountEstimator;
    private final TokenBucketRateLimiter rateLimiter;
    private final List<String> models;

    public ChatStreamHandler(
            ChatModel chatModel,
            ChatMessageRepository chatMessageRepository,
            CitationMapper citationMapper,
            TokenCountEstimator tokenCountEstimator,
            @Qualifier("groqRateLimiter") TokenBucketRateLimiter rateLimiter,
            @Value("${spring.ai.openai.chat.options.model}") String primaryModel,
            @Value("${app.groq.fallback-models:}") String fallbackModelsCsv) {
        this.chatModel = chatModel;
        this.chatMessageRepository = chatMessageRepository;
        this.citationMapper = citationMapper;
        this.tokenCountEstimator = tokenCountEstimator;
        this.rateLimiter = rateLimiter;

        List<String> resolvedModels = new ArrayList<>();
        resolvedModels.add(primaryModel);
        if (fallbackModelsCsv != null && !fallbackModelsCsv.isBlank()) {
            for (String candidate : fallbackModelsCsv.split(",")) {
                if (!candidate.isBlank()) {
                    resolvedModels.add(candidate.trim());
                }
            }
        }
        this.models = List.copyOf(resolvedModels);
    }

    public SseEmitter stream(
            UUID sessionId,
            ChatMessageResponse savedUserMessage,
            List<CitationDto> citations,
            String systemPrompt,
            String userPrompt) {

        SseEmitter emitter = new SseEmitter(RagSettings.STREAM_TIMEOUT_MS);
        StringBuilder fullReply = new StringBuilder();

        try {
            emitter.send(SseEmitter.event()
                    .name("user_message")
                    .data(savedUserMessage));

            streamWithFallback(systemPrompt, userPrompt, 0, fullReply)
                    .doOnNext(token -> appendToken(emitter, fullReply, token))
                    .doOnError(err -> {
                        log.error("Chat stream error", err);
                        handleStreamFailure(emitter, sessionId, fullReply, citations, err);
                    })
                    .doOnComplete(() -> completeStream(
                            emitter, sessionId, fullReply, citations))
                    .subscribe();
        } catch (Exception ex) {
            log.error("Chat stream error", ex);
            handleStreamFailure(emitter, sessionId, fullReply, citations, ex);
        }

        return emitter;
    }

    /**
     * Tries each configured model in order, falling back to the next only if the current one
     * errors out before producing any output. Once tokens have already reached the client under
     * one model, switching mid-answer would splice together two different models' output, so a
     * failure past that point always propagates instead of retrying.
     */
    private Flux<String> streamWithFallback(
            String systemPrompt, String userPrompt, int modelIndex, StringBuilder fullReply) {
        String model = models.get(modelIndex);
        int estimatedTokens = tokenCountEstimator.estimate(systemPrompt) + tokenCountEstimator.estimate(userPrompt);
        rateLimiter.acquire(estimatedTokens);

        Flux<String> attempt = ChatClient.builder(chatModel)
                .build()
                .prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .options(OpenAiChatOptions.builder().model(model))
                .stream()
                .content();

        if (modelIndex + 1 >= models.size()) {
            return attempt;
        }
        return attempt.onErrorResume(err -> {
            if (!fullReply.isEmpty()) {
                return Flux.error(err);
            }
            log.warn("Model {} failed before producing output ({}); falling back to {}",
                    model, err.getMessage(), models.get(modelIndex + 1));
            return streamWithFallback(systemPrompt, userPrompt, modelIndex + 1, fullReply);
        });
    }

    /**
     * On failure, persist whatever partial reply was generated so far (instead of discarding
     * it) before telling the client the stream failed — otherwise a mid-stream error leaves the
     * user's question orphaned with no answer in history, even if the model had already
     * produced a partial one.
     */
    private void handleStreamFailure(
            SseEmitter emitter,
            UUID sessionId,
            StringBuilder fullReply,
            List<CitationDto> citations,
            Throwable err) {
        if (!fullReply.isEmpty()) {
            try {
                persistAssistantMessage(emitter, sessionId, fullReply, citations);
            } catch (Exception persistEx) {
                log.warn("Failed to persist partial assistant reply", persistEx);
            }
        }
        sendErrorAndComplete(emitter, err);
    }

    /**
     * Ends the SSE response via complete() rather than completeWithError(): the latter makes
     * Spring MVC re-dispatch the request through the normal @ExceptionHandler chain, which then
     * fails trying to write a JSON error body onto a response whose Content-Type is already
     * locked to text/event-stream.
     */
    private void sendErrorAndComplete(SseEmitter emitter, Throwable err) {
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data("The assistant is temporarily unavailable. Please try again.", MediaType.APPLICATION_JSON));
        } catch (Exception sendEx) {
            log.warn("Failed to send SSE error event", sendEx);
        } finally {
            emitter.complete();
        }
    }

    private void appendToken(SseEmitter emitter, StringBuilder fullReply, String token) {
        fullReply.append(token);
        try {
            emitter.send(SseEmitter.event()
                    .name("token")
                    .data(token, MediaType.APPLICATION_JSON));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void completeStream(
            SseEmitter emitter,
            UUID sessionId,
            StringBuilder fullReply,
            List<CitationDto> citations) {
        try {
            persistAssistantMessage(emitter, sessionId, fullReply, citations);
            emitter.send(SseEmitter.event().name("done").data("[DONE]"));
            emitter.complete();
        } catch (Exception ex) {
            log.error("Failed to finalize chat stream", ex);
            sendErrorAndComplete(emitter, ex);
        }
    }

    private void persistAssistantMessage(
            SseEmitter emitter,
            UUID sessionId,
            StringBuilder fullReply,
            List<CitationDto> citations) throws Exception {
        ChatMessage assistant = chatMessageRepository.save(ChatMessage.builder()
                .sessionId(sessionId)
                .role(MessageRole.ASSISTANT)
                .content(fullReply.toString())
                .citations(citationMapper.toJson(citations))
                .build());

        emitter.send(SseEmitter.event()
                .name("assistant_message")
                .data(toMessageResponse(assistant)));
    }

    private ChatMessageResponse toMessageResponse(ChatMessage message) {
        return new ChatMessageResponse(
                message.getId(),
                message.getRole(),
                message.getContent(),
                citationMapper.fromJson(message.getCitations()),
                message.getCreatedAt());
    }
}