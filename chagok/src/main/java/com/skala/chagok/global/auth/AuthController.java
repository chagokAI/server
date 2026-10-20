package com.skala.chagok.global.auth;

import com.skala.chagok.domain.user.repository.AppUserRepository;
import com.skala.chagok.global.error.*;
import com.skala.chagok.global.response.*;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.*;

@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "chagok.auth.enabled", havingValue = "true", matchIfMissing = true)
@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/auth")
public class AuthController {
    private final AppUserRepository users;
    private final CurrentUserProvider current;
    private final PasswordEncoder passwords;
    private final SecurityContextRepository contexts;
    private final ApiResponses responses;
    private final org.springframework.security.web.csrf.CsrfTokenRepository csrfTokens;
    @org.springframework.beans.factory.annotation.Value("${server.servlet.session.cookie.secure:false}")
    private boolean secureCookie;

    public record CsrfResponse(String headerName, String token) {}
    @GetMapping("/csrf")
    public ApiResponse<CsrfResponse> csrf(CsrfToken token) {
        return responses.of(new CsrfResponse(token.getHeaderName(), token.getToken()));
    }

    @PostMapping("/login")
    public ApiResponse<UserSession> login(@Valid @RequestBody LoginRequest input,
            HttpServletRequest request, HttpServletResponse response) {
        var found = users.findByEmployeeNo(input.employeeId().trim());
        var user = found.size() == 1 ? found.getFirst() : null;
        var hash = user == null ? null : user.getPasswordHash();
        if (hash != null && hash.startsWith("{bcrypt}")) hash = hash.substring(8);
        boolean valid;
        try { valid = hash != null && passwords.matches(input.password(), hash); }
        catch (IllegalArgumentException e) { valid = false; }
        if (!valid || user.getWarehouse() == null)
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "사번 또는 비밀번호가 올바르지 않습니다.");
        String role = user.getRole() == null ? "VIEWER" : user.getRole().toUpperCase(java.util.Locale.ROOT);
        if (!List.of("VIEWER", "OPERATOR", "ADMIN").contains(role)) throw new BusinessException(ErrorCode.FORBIDDEN);
        var session = request.getSession(true);
        request.changeSessionId();
        session.setMaxInactiveInterval(Boolean.TRUE.equals(input.rememberMe()) ? 7 * 86400 : 1800);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(user.getId().toString(), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        csrfTokens.saveToken(null, request, response);
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from("JSESSIONID", session.getId()).httpOnly(true)
                .secure(secureCookie || request.isSecure()).sameSite("Lax").path("/")
                .maxAge(Boolean.TRUE.equals(input.rememberMe()) ? Duration.ofDays(7) : Duration.ofSeconds(-1)).build().toString());
        return responses.of(UserSession.from(user));
    }

    @GetMapping("/me")
    public ApiResponse<UserSession> me() { return responses.of(UserSession.from(current.getCurrentUser())); }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        new SecurityContextLogoutHandler().logout(request, response, SecurityContextHolder.getContext().getAuthentication());
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from("JSESSIONID", "").httpOnly(true)
                .secure(secureCookie || request.isSecure()).sameSite("Lax").path("/").maxAge(0).build().toString());
        return ResponseEntity.noContent().build();
    }
}
