package devPilot.backend.config;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import devPilot.backend.security.AppUserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Per-user (or per-IP, for the unauthenticated login-url endpoint) request throttle for our own
 * REST API — a separate concern from the outbound LLM rate limiters: this one rejects (429)
 * instead of waiting, since blocking a Tomcat worker thread to smooth out an abusive caller
 * would be worse than just telling them to slow down.
 */
@Component
public class ApiRateLimitInterceptor implements HandlerInterceptor {

    // Client IPs have no natural cardinality cap the way our own user base does; this bounds
    // the map's worst-case memory under sustained scanning/abuse rather than tracking every
    // caller forever.
    private static final int MAX_TRACKED_KEYS = 10_000;

    private final int limitPerMinute;
    private final JsonMapper jsonMapper;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public ApiRateLimitInterceptor(
            @Value("${app.rate-limit.api.rpm:120}") int limitPerMinute,
            JsonMapper jsonMapper) {
        this.limitPerMinute = limitPerMinute;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (windows.size() > MAX_TRACKED_KEYS) {
            windows.clear();
        }
        Window window = windows.computeIfAbsent(resolveKey(request), k -> new Window());
        if (window.tryConsume(limitPerMinute)) {
            return true;
        }

        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = Map.of(
                "status", 429,
                "error", "Too Many Requests",
                "message", "You're sending requests too quickly. Please slow down and try again shortly.",
                "timestamp", Instant.now().toString());
        response.getWriter().write(jsonMapper.writeValueAsString(body));
        return false;
    }

    private String resolveKey(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUserPrincipal principal) {
            return "user:" + principal.getId();
        }
        return "ip:" + request.getRemoteAddr();
    }

    private static final class Window {
        private int count = 0;
        private long windowStartMillis = System.currentTimeMillis();

        synchronized boolean tryConsume(int limit) {
            long now = System.currentTimeMillis();
            if (now - windowStartMillis >= 60_000) {
                windowStartMillis = now;
                count = 0;
            }
            count++;
            return count <= limit;
        }
    }
}
