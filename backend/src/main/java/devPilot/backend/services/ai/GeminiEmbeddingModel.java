package devPilot.backend.services.ai;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import devPilot.backend.exceptions.ExternalServiceException;
import devPilot.backend.services.ratelimit.TokenBucketRateLimiter;

@Component
@Primary
public class GeminiEmbeddingModel implements EmbeddingModel {

    private final RestClient restClient;
    private final String apiKey;
    private final String model;
    private final int dimensions;
    private final TokenCountEstimator tokenCountEstimator;
    private final TokenBucketRateLimiter rateLimiter;

    public GeminiEmbeddingModel(
            @Value("${spring.ai.google.genai.api-key}") String apiKey,
            @Value("${spring.ai.google.genai.embedding.options.model}") String model,
            @Value("${spring.ai.vectorstore.pgvector.dimensions}") int dimensions,
            TokenCountEstimator tokenCountEstimator,
            @Qualifier("geminiRateLimiter") TokenBucketRateLimiter rateLimiter) {
        this.apiKey = apiKey;
        this.model = model;
        this.dimensions = dimensions;
        this.tokenCountEstimator = tokenCountEstimator;
        this.rateLimiter = rateLimiter;
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        this.restClient = RestClient.builder()
                .baseUrl("https://generativelanguage.googleapis.com/v1beta/openai/v1/embeddings")
                .requestFactory(requestFactory)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    throw translateError(response.getStatusCode().value());
                })
                .build();
    }

    private static ExternalServiceException translateError(int status) {
        String message = switch (status) {
            case 401, 403 -> "The AI embedding service rejected the request. Please check the service configuration.";
            case 429 -> "The AI embedding service is rate-limited right now.";
            default -> status >= 500
                    ? "The AI embedding service is temporarily unavailable. Please try again later."
                    : "The AI embedding service request failed (status " + status + ").";
        };
        return new ExternalServiceException("Gemini", status, message);
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    @Override
    public float[] embed(String text) {
        return call(new EmbeddingRequest(List.of(text), null)).getResults().get(0).getOutput();
    }

    public List<float[]> embed(List<String> texts, EmbeddingOptions options) {
        List<float[]> results = new ArrayList<>();
        for (Embedding embedding : call(new EmbeddingRequest(texts, options)).getResults()) {
            results.add(embedding.getOutput());
        }
        return results;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        int estimatedTokens = request.getInstructions().stream()
                .mapToInt(tokenCountEstimator::estimate)
                .sum();
        rateLimiter.acquire(estimatedTokens);

        Map<String, Object> body = Map.of(
                "input", request.getInstructions(),
                "model", this.model,
                "dimensions", this.dimensions
        );

        Map response = restClient.post()
                .header("Authorization", "Bearer " + apiKey)
                .body(body)
                .retrieve()
                .body(Map.class);

        if (response == null || !(response.get("data") instanceof List<?> rawData)) {
            throw new ExternalServiceException("Gemini", 0,
                    "The AI embedding service returned an unexpected response. Please try again.");
        }
        List<Map<String, Object>> data = (List<Map<String, Object>>) rawData;
        List<Embedding> embeddings = new ArrayList<>();
        
        for (int i = 0; i < data.size(); i++) {
            Map<String, Object> d = data.get(i);
            List<Double> vector = (List<Double>) d.get("embedding");
            float[] floatVector = new float[vector.size()];
            for (int j = 0; j < vector.size(); j++) {
                floatVector[j] = vector.get(j).floatValue();
            }
            embeddings.add(new Embedding(floatVector, i));
        }

        return new EmbeddingResponse(embeddings);
    }
}
