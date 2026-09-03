package devPilot.backend.services.ratelimit;

import lombok.extern.slf4j.Slf4j;

/**
 * Continuous-refill token bucket. One instance represents one shared account-wide quota (e.g.
 * "this Gemini API key gets 100 requests and 30k tokens per minute, across every caller in the
 * JVM") — callers pace themselves against it instead of bursting and reacting to 429s after the
 * fact.
 *
 * <p>Also reused for inbound per-user API throttling via {@link #tryAcquire}, passing a very
 * large token budget so only the request-count dimension binds.
 */
@Slf4j
public class TokenBucketRateLimiter {
    private final String name;
    private final int maxRequestsPerMinute;
    private final int maxTokensPerMinute;
    private final Object lock = new Object();
    private double requestCredits;
    private double tokenCredits;
    private long lastRefillNanos;

    public TokenBucketRateLimiter(String name, int maxRequestsPerMinute, int maxTokensPerMinute) {
        this.name = name;
        this.maxRequestsPerMinute = Math.max(1, maxRequestsPerMinute);
        this.maxTokensPerMinute = Math.max(1, maxTokensPerMinute);
        this.requestCredits = this.maxRequestsPerMinute;
        this.tokenCredits = this.maxTokensPerMinute;
        this.lastRefillNanos = System.nanoTime();
    }

    /** Blocks the calling thread until both a request slot and the token budget are available. */
    public void acquire(int estimatedTokens) {
        int need = Math.max(1, estimatedTokens);
        while (true) {
            long waitMillis;
            synchronized (lock) {
                refill();
                if (requestCredits >= 1 && tokenCredits >= need) {
                    requestCredits -= 1;
                    tokenCredits -= need;
                    return;
                }
                waitMillis = millisUntilAvailable(need);
            }
            log.debug("{} rate limit: waiting {}ms for capacity ({} tokens requested)", name, waitMillis, need);
            sleep(waitMillis);
        }
    }

    /** Non-blocking variant for guarding inbound requests: never waits, just reports capacity. */
    public boolean tryAcquire(int estimatedTokens) {
        int need = Math.max(1, estimatedTokens);
        synchronized (lock) {
            refill();
            if (requestCredits >= 1 && tokenCredits >= need) {
                requestCredits -= 1;
                tokenCredits -= need;
                return true;
            }
            return false;
        }
    }

    private long millisUntilAvailable(int need) {
        double missingRequests = Math.max(0, 1 - requestCredits);
        double missingTokens = Math.max(0, need - tokenCredits);
        double waitForRequestsMs = missingRequests / maxRequestsPerMinute * 60_000;
        double waitForTokensMs = missingTokens / maxTokensPerMinute * 60_000;
        return (long) Math.ceil(Math.max(waitForRequestsMs, waitForTokensMs)) + 5;
    }

    private void refill() {
        long now = System.nanoTime();
        double elapsedMinutes = (now - lastRefillNanos) / 60_000_000_000.0;
        if (elapsedMinutes <= 0) {
            return;
        }
        requestCredits = Math.min(maxRequestsPerMinute, requestCredits + elapsedMinutes * maxRequestsPerMinute);
        tokenCredits = Math.min(maxTokensPerMinute, tokenCredits + elapsedMinutes * maxTokensPerMinute);
        lastRefillNanos = now;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(Math.max(1, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while rate limiting", e);
        }
    }
}
