package edu.chd.practice.web.controller;

import edu.chd.practice.web.api.ApiResponse;
import edu.chd.practice.web.config.AppProperties;
import edu.chd.practice.web.dto.AuthDtos;
import edu.chd.practice.web.security.JwtAuthenticationFilter;
import edu.chd.practice.web.security.JwtService;
import edu.chd.practice.web.security.LoginRateLimiter;
import edu.chd.practice.web.security.SecuritySupport;
import edu.chd.practice.web.security.UserPrincipal;
import edu.chd.practice.web.service.AuditService;
import edu.chd.practice.web.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private final AuthService authService;
    private final JwtService jwtService;
    private final LoginRateLimiter rateLimiter;
    private final AuditService auditService;
    private final CsrfTokenRepository csrfTokenRepository;
    private final boolean trustProxy;

    public AuthController(AuthService authService, JwtService jwtService, LoginRateLimiter rateLimiter,
                          AuditService auditService, AppProperties properties,
                          CsrfTokenRepository csrfTokenRepository) {
        this.authService = authService;
        this.jwtService = jwtService;
        this.rateLimiter = rateLimiter;
        this.auditService = auditService;
        this.csrfTokenRepository = csrfTokenRepository;
        this.trustProxy = properties.trustProxy();
    }

    @PostMapping("/login")
    public ApiResponse<AuthDtos.CurrentUser> login(@Valid @RequestBody AuthDtos.LoginRequest input,
                                                    HttpServletRequest request,
                                                    HttpServletResponse response) {
        String clientAddress = clientAddress(request);
        rateLimiter.check(clientAddress, input.username());
        UserPrincipal principal;
        try {
            principal = authService.authenticate(input.username(), input.password());
        } catch (RuntimeException exception) {
            rateLimiter.failed(clientAddress, input.username());
            recordFailedLogin(input.username(), clientAddress);
            throw exception;
        }
        AuthDtos.CurrentUser current = authService.current(principal);
        String token = jwtService.create(principal);
        auditService.recordAs(principal.id(), "LOGIN_SUCCESS", "users", principal.id(), true,
                "address=" + clientAddress);
        rateLimiter.succeeded(clientAddress, input.username());
        response.addHeader(HttpHeaders.SET_COOKIE, authCookie(token,
                jwtService.ttlSeconds(), request.isSecure()).toString());
        rotateCsrfToken(request, response);
        return ApiResponse.ok(current);
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        UserPrincipal principal = SecuritySupport.principal();
        authToken(request).ifPresent(jwtService::revoke);
        response.addHeader(HttpHeaders.SET_COOKIE, authCookie("", 0, request.isSecure()).toString());
        rotateCsrfToken(request, response);
        auditService.record("LOGOUT", "users", principal.id(), true, "");
        return ApiResponse.ok(null);
    }

    @GetMapping("/me")
    public ApiResponse<AuthDtos.CurrentUser> me() {
        return ApiResponse.ok(authService.current(SecuritySupport.principal()));
    }

    @GetMapping("/csrf")
    public ApiResponse<AuthDtos.Csrf> csrf(CsrfToken token) {
        return ApiResponse.ok(new AuthDtos.Csrf(token.getHeaderName(), token.getParameterName(), token.getToken()));
    }

    private ResponseCookie authCookie(String value, long maxAge, boolean secureRequest) {
        return ResponseCookie.from(JwtAuthenticationFilter.COOKIE_NAME, value)
                .httpOnly(true).secure(secureRequest).sameSite("Strict").path("/").maxAge(maxAge).build();
    }

    private void rotateCsrfToken(HttpServletRequest request, HttpServletResponse response) {
        csrfTokenRepository.saveToken(null, request, response);
        csrfTokenRepository.saveToken(csrfTokenRepository.generateToken(request), request, response);
    }

    private void recordFailedLogin(String username, String clientAddress) {
        try {
            auditService.recordAs("anonymous", "LOGIN_FAILURE", "users", username, false,
                    "address=" + clientAddress);
        } catch (RuntimeException auditFailure) {
            log.error("Failed to persist login-failure audit for address {}", clientAddress, auditFailure);
        }
    }

    private String clientAddress(HttpServletRequest request) {
        if (trustProxy) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",", 2)[0].strip();
            }
        }
        return request.getRemoteAddr();
    }

    private java.util.Optional<String> authToken(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return java.util.Optional.empty();
        }
        return java.util.Arrays.stream(request.getCookies())
                .filter(cookie -> JwtAuthenticationFilter.COOKIE_NAME.equals(cookie.getName()))
                .map(Cookie::getValue).filter(value -> !value.isBlank()).findFirst();
    }
}
