package com.skala.chagok.global.config;

import com.skala.chagok.global.auth.*;
import com.skala.chagok.global.error.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.*;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.*;
import org.springframework.security.web.csrf.*;
import org.springframework.web.cors.*;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean SecurityContextRepository securityContexts() { return new HttpSessionSecurityContextRepository(); }
    @Bean CsrfTokenRepository csrfTokens() { return new HttpSessionCsrfTokenRepository(); }

    /**
     * 에이전트 business-mcp 전용 /internal/** . 세션·CSRF 없이 X-Internal-Token 헤더 하나로 인증한다.
     * 토큰(INTERNAL_API_TOKEN)이 비어 있으면 모두 거부한다. docker network 밖에는 이 경로를 열지 않는다.
     */
    @Bean @org.springframework.core.annotation.Order(1)
    SecurityFilterChain internalApiChain(HttpSecurity http, @Value("${chagok.internal.token:}") String token) throws Exception {
        byte[] expected = token.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return http.securityMatcher("/internal/**").httpBasic(basic -> basic.disable()).formLogin(form -> form.disable())
                .logout(logout -> logout.disable()).csrf(csrf -> csrf.disable()).requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(
                        org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(new OncePerRequestFilter() {
                    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                            FilterChain chain) throws ServletException, IOException {
                        String given = request.getHeader("X-Internal-Token");
                        if (expected.length == 0 || given == null || !java.security.MessageDigest.isEqual(expected,
                                given.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                            write(request, response, ErrorCode.UNAUTHENTICATED); return;
                        }
                        chain.doFilter(request, response);
                    }
                }, AuthorizationFilter.class).build();
    }

    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityContextRepository contexts,
            CsrfTokenRepository tokens, CurrentUserProvider users,
            @Value("${chagok.auth.enabled:true}") boolean enabled) throws Exception {
        http.httpBasic(basic -> basic.disable()).formLogin(form -> form.disable()).logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable()).cors(Customizer.withDefaults())
                .securityContext(context -> context.securityContextRepository(contexts));
        if (!enabled) return http.csrf(csrf -> csrf.disable()).authorizeHttpRequests(auth -> auth.anyRequest().permitAll()).build();
        http.csrf(csrf -> csrf.csrfTokenRepository(tokens))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/csrf", "/api/v1/auth/login", "/actuator/health", "/error").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/**").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/warehouses/*/notifications/*/read").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/warehouses/*/notifications/read-all").authenticated()
                        .requestMatchers("/api/v1/**").hasAnyRole("OPERATOR", "ADMIN")
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, error) -> write(request, response, ErrorCode.UNAUTHENTICATED))
                        .accessDeniedHandler((request, response, error) -> write(request, response, ErrorCode.FORBIDDEN)))
                .addFilterBefore(new OncePerRequestFilter() {
                    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                            FilterChain chain) throws ServletException, IOException {
                        var authentication = SecurityContextHolder.getContext().getAuthentication();
                        var path = request.getRequestURI().substring(request.getContextPath().length());
                        if (authentication != null && authentication.isAuthenticated() && path.startsWith("/api/v1/warehouses/")) {
                            String segment = path.substring("/api/v1/warehouses/".length()).split("/")[0];
                            UUID warehouseId = null;
                            try { warehouseId = UUID.fromString(segment); } catch (IllegalArgumentException ignored) { }
                            if (warehouseId != null) {
                                try {
                                    var user = users.getCurrentUser();
                                    if (user.getWarehouse() == null || !warehouseId.equals(user.getWarehouse().getId())) {
                                        write(request, response, ErrorCode.FORBIDDEN); return;
                                    }
                                } catch (BusinessException e) { write(request, response, e.getErrorCode()); return; }
                            }
                        }
                        chain.doFilter(request, response);
                    }
                }, AuthorizationFilter.class);
        return http.build();
    }

    @Bean CorsConfigurationSource corsConfigurationSource(
            @Value("${chagok.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}") String origins) {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type", "X-CSRF-TOKEN", "X-Request-Id"));
        config.setExposedHeaders(List.of("Location"));
        config.setAllowCredentials(true);
        var source = new UrlBasedCorsConfigurationSource(); source.registerCorsConfiguration("/api/**", config); return source;
    }

    private static void write(HttpServletRequest request, HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.getStatus().value()); response.setContentType("application/json;charset=UTF-8");
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank()) requestId = "req-" + UUID.randomUUID();
        response.getWriter().write(JsonMapper.builder().build().writeValueAsString(
                ErrorResponse.of(code, code.getDefaultMessage(), List.of(), requestId)));
    }
}
