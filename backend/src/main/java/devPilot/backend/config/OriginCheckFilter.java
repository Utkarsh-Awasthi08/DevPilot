package devPilot.backend.config;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class OriginCheckFilter extends OncePerRequestFilter {

    private final List<String> allowedOrigins;

    public OriginCheckFilter(@Value("${app.cors.allowed-origins}") String allowedOrigins) {
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .toList();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String method = request.getMethod();
        // Only check mutating requests (CSRF mitigation)
        if (HttpMethod.POST.matches(method) || HttpMethod.PUT.matches(method) ||
                HttpMethod.PATCH.matches(method) || HttpMethod.DELETE.matches(method)) {
            
            String origin = request.getHeader("Origin");
            String referer = request.getHeader("Referer");

            // If neither header is present, it could be a programmatic API call. 
            // In a strict browser-only CSRF defense, we'd block this.
            // But we'll at least verify that if an Origin/Referer IS sent, it's trusted.
            String source = origin != null ? origin : referer;
            
            if (source != null) {
                boolean isTrusted = false;
                for (String allowedOrigin : allowedOrigins) {
                    if (source.startsWith(allowedOrigin)) {
                        isTrusted = true;
                        break;
                    }
                }
                if (!isTrusted) {
                    response.sendError(HttpStatus.FORBIDDEN.value(), "Untrusted origin");
                    return;
                }
            }
        }

        filterChain.doFilter(request, response);
    }
}
