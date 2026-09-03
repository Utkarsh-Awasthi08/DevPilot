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
     * Paces outbound Gemini embedding calls so the app stays under the API key's shared
     * RPM/TPM quota proactively, instead of bursting and reacting to 429s afterward. Defaults
     * match Gemini's published free-tier limits for gemini-embedding-001 — adjust if your plan
     * differs.
     */
    @Bean(name = "geminiRateLimiter")
    TokenBucketRateLimiter geminiRateLimiter(
            @Value("${app.rate-limit.gemini.rpm:100}") int rpm,
            @Value("${app.rate-limit.gemini.tpm:30000}") int tpm) {
        return new TokenBucketRateLimiter("Gemini embeddings", rpm, tpm);
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
