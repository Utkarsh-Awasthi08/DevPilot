package devPilot.backend.config;

import java.time.Instant;
import java.util.Map;

import devPilot.backend.security.GithubOAuth2UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.*;

import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

        private final GithubOAuth2UserService githubOAuth2UserService;

        @Bean
        SecurityFilterChain securityFilterChain(
                        HttpSecurity http,
                        AuthenticationSuccessHandler oauth2SuccessHandler,
                        AuthenticationFailureHandler oauth2FailureHandler,
                        AuthenticationEntryPoint restAuthenticationEntryPoint) throws Exception {
                http
                                .cors(Customizer.withDefaults())
                                .csrf(csrf -> csrf.disable())
                                .sessionManagement(session -> session
                                                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                                .authorizeHttpRequests(auth -> auth
                                                .requestMatchers(
                                                                "/api/auth/login-url",
                                                                "/oauth2/**",
                                                                "/login/oauth2/**",
                                                                "/error")
                                                .permitAll()
                                                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                                                .requestMatchers("/api/**").authenticated()
                                                .anyRequest().permitAll())
                                .exceptionHandling(ex -> ex
                                                .authenticationEntryPoint(restAuthenticationEntryPoint))
                                .oauth2Login(oauth -> oauth
                                                .userInfoEndpoint(userInfo -> userInfo
                                                                .userService(githubOAuth2UserService))
                                                .successHandler(oauth2SuccessHandler)
                                                .failureHandler(oauth2FailureHandler))
                                .logout(logout -> logout
                                                .logoutUrl("/api/auth/logout")
                                                .logoutSuccessHandler((request, response, authentication) -> response
                                                                .setStatus(HttpStatus.NO_CONTENT.value()))
                                                .invalidateHttpSession(true)
                                                .clearAuthentication(true)
                                                .deleteCookies("DEVPILOT_SESSION"));

                return http.build();
        }

        /**
         * The Spring Security filter chain rejects an unauthenticated /api/** request BEFORE
         * any controller runs, so GlobalExceptionHandler never sees it — without this, that
         * request (the most common auth failure in the app) fell through to Spring Boot's
         * default /error page instead of the app's normal {status,error,message,timestamp}
         * JSON shape.
         */
        @Bean
        AuthenticationEntryPoint restAuthenticationEntryPoint(JsonMapper jsonMapper) {
                return (request, response, authException) -> {
                        response.setStatus(HttpStatus.UNAUTHORIZED.value());
                        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                        Map<String, Object> body = Map.of(
                                        "status", HttpStatus.UNAUTHORIZED.value(),
                                        "error", HttpStatus.UNAUTHORIZED.getReasonPhrase(),
                                        "message", "Authentication required. Please log in.",
                                        "timestamp", Instant.now().toString());
                        response.getWriter().write(jsonMapper.writeValueAsString(body));
                };
        }

        @Bean
        AuthenticationSuccessHandler oauth2SuccessHandler(
                        @Value("${app.frontend.url}") String frontendUrl) {
                SimpleUrlAuthenticationSuccessHandler handler = new SimpleUrlAuthenticationSuccessHandler();
                handler.setDefaultTargetUrl(frontendUrl + "/auth/callback");
                return handler;
        }

        @Bean
        AuthenticationFailureHandler oauth2FailureHandler(
                        @Value("${app.frontend.url}") String frontendUrl) {
                SimpleUrlAuthenticationFailureHandler handler = new SimpleUrlAuthenticationFailureHandler();
                handler.setDefaultFailureUrl(frontendUrl + "/login?error=oauth_failed");
                return handler;
        }
}
