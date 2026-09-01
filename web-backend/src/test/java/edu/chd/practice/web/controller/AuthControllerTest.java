package edu.chd.practice.web.controller;

import edu.chd.practice.web.config.AppProperties;
import edu.chd.practice.web.dto.AuthDtos;
import edu.chd.practice.web.error.ApiException;
import edu.chd.practice.web.security.JwtService;
import edu.chd.practice.web.security.LoginRateLimiter;
import edu.chd.practice.web.security.UserPrincipal;
import edu.chd.practice.web.service.AuditService;
import edu.chd.practice.web.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerTest {
    private AuthService authService;
    private JwtService jwtService;
    private LoginRateLimiter rateLimiter;
    private AuditService auditService;
    private CsrfTokenRepository csrfTokenRepository;
    private AuthController controller;
    private HttpServletRequest request;
    private HttpServletResponse response;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        jwtService = mock(JwtService.class);
        rateLimiter = mock(LoginRateLimiter.class);
        auditService = mock(AuditService.class);
        csrfTokenRepository = mock(CsrfTokenRepository.class);
        controller = new AuthController(authService, jwtService, rateLimiter, auditService,
                new AppProperties(null, null, null, false), csrfTokenRepository);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        when(request.getRemoteAddr()).thenReturn("192.0.2.10");
    }

    @Test
    void successAuditFailureDoesNotEmitAnAuthenticationCookie() {
        UserPrincipal principal = principal();
        RuntimeException outage = new RuntimeException("audit unavailable");
        when(authService.authenticate("teacher", "password")).thenReturn(principal);
        when(jwtService.create(principal)).thenReturn("jwt");
        doThrow(outage).when(auditService).recordAs(
                eq("user-1"), eq("LOGIN_SUCCESS"), eq("users"), eq("user-1"), eq(true), anyString());

        assertThatThrownBy(() -> controller.login(
                new AuthDtos.LoginRequest("teacher", "password"), request, response)).isSameAs(outage);

        verify(response, never()).addHeader(eq(HttpHeaders.SET_COOKIE), anyString());
        verify(rateLimiter, never()).succeeded("192.0.2.10", "teacher");
    }

    @Test
    void failureAuditOutageDoesNotMaskTheAuthenticationError() {
        ApiException invalid = new ApiException(HttpStatus.UNAUTHORIZED,
                "INVALID_CREDENTIALS", "invalid credentials");
        when(authService.authenticate("teacher", "wrong")).thenThrow(invalid);
        doThrow(new RuntimeException("audit unavailable")).when(auditService).recordAs(
                eq("anonymous"), eq("LOGIN_FAILURE"), eq("users"), eq("teacher"), eq(false), anyString());

        assertThatThrownBy(() -> controller.login(
                new AuthDtos.LoginRequest("teacher", "wrong"), request, response)).isSameAs(invalid);

        verify(rateLimiter).failed("192.0.2.10", "teacher");
        verify(response, never()).addHeader(eq(HttpHeaders.SET_COOKIE), anyString());
    }

    @Test
    void successfulLoginRotatesTheCsrfCookieOnceAuthenticationIsEstablished() {
        UserPrincipal principal = principal();
        CsrfToken nextToken = mock(CsrfToken.class);
        when(authService.authenticate("teacher", "password")).thenReturn(principal);
        when(jwtService.create(principal)).thenReturn("jwt");
        when(jwtService.ttlSeconds()).thenReturn(3600L);
        when(csrfTokenRepository.generateToken(request)).thenReturn(nextToken);

        controller.login(new AuthDtos.LoginRequest("teacher", "password"), request, response);

        verify(csrfTokenRepository).saveToken(null, request, response);
        verify(csrfTokenRepository).saveToken(nextToken, request, response);
    }

    private UserPrincipal principal() {
        return new UserPrincipal("user-1", "teacher", "Teacher", null,
                Set.of("TEACHER"), Set.of("GRADE_READ"), true);
    }
}
