package devPilot.backend.config;

import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import devPilot.backend.services.ratelimit.TokenBucketRateLimiter;

@Configuration
public class RateLimitConfig {

    @Bean
    TokenCountEstimator tokenCountEstimator() {
        return new JTokkitTokenCountEstimator();
    }

    /**
     * Paces outbound embedding calls so the app stays under the API key's shared
     * RPM/TPM quota proactively.
     */
    @Bean(name = "embeddingRateLimiter")
    TokenBucketRateLimiter embeddingRateLimiter(
            @Value("${app.rate-limit.embedding.rpm:60}") int rpm,
            @Value("${app.rate-limit.embedding.tpm:20000000}") int tpm) {
        return new TokenBucketRateLimiter("Embeddings", rpm, tpm);
    }

    /**
     * Paces outbound Groq chat calls the same way. Defaults match this account's Free Plan
     * limits for openai/gpt-oss-120b (console.groq.com/docs/rate-limits: 30 RPM / 8K TPM) —
     * every model in the fallback chain shares these same limits but each has its own separate
     * quota bucket on Groq's side, which is what makes falling back to another model useful.
     * Note this only paces requests-per-minute/tokens-per-minute; Groq's per-model daily caps
     * (RPD/TPD) aren't tracked here.
     */
    @Bean(name = "groqRateLimiter")
    TokenBucketRateLimiter groqRateLimiter(
            @Value("${app.rate-limit.groq.rpm:30}") int rpm,
            @Value("${app.rate-limit.groq.tpm:8000}") int tpm) {
        return new TokenBucketRateLimiter("Groq chat", rpm, tpm);
    }
}
