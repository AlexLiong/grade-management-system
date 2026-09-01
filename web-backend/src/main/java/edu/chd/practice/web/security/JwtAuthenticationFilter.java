package edu.chd.practice.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.chd.practice.web.api.ApiError;
import edu.chd.practice.web.api.ApiResponse;
import edu.chd.practice.web.error.ApiException;
import edu.chd.practice.web.service.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    public static final String COOKIE_NAME = "AUTH_TOKEN";
    private final JwtService jwtService;
    private final AuthService authService;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(JwtService jwtService, AuthService authService, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.authService = authService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = token(request).orElse(null);
            if (token != null && !authenticate(token, request, response)) {
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private boolean authenticate(String token, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        try {
            UserPrincipal tokenPrincipal = jwtService.parse(token);
            UserPrincipal principal = authService.refresh(tokenPrincipal);
            UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
                    principal, token, principal.getAuthorities());
            authentication.setDetails(request.getRemoteAddr());
            SecurityContextHolder.getContext().setAuthentication(authentication);
            return true;
        } catch (ApiException exception) {
            SecurityContextHolder.clearContext();
            if (exception.status() == HttpStatus.UNAUTHORIZED) {
                return true;
            }
            writeError(response, request, exception);
            return false;
        } catch (RuntimeException ignored) {
            SecurityContextHolder.clearContext();
            return true;
        }
    }

    private void writeError(HttpServletResponse response, HttpServletRequest request,
                            ApiException exception) throws IOException {
        response.setStatus(exception.status().value());
        response.setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        Object traceId = request.getAttribute("traceId");
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.failure(new ApiError(
                exception.code(), exception.getMessage(), traceId == null ? "unknown" : traceId.toString(),
                Map.of())));
    }

    private java.util.Optional<String> token(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return java.util.Optional.empty();
        }
        return Arrays.stream(request.getCookies()).filter(cookie -> COOKIE_NAME.equals(cookie.getName()))
                .map(Cookie::getValue).filter(value -> !value.isBlank()).findFirst();
    }
}
