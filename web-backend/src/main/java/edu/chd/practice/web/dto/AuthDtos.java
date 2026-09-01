package edu.chd.practice.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Set;

public final class AuthDtos {
    private AuthDtos() {
    }

    public record LoginRequest(
            @NotBlank @Size(max = 64) String username,
            @NotBlank @Size(max = 128) String password
    ) {
    }

    public record CurrentUser(String id, String username, String displayName, String organizationId,
                              String role, Set<String> roles, Set<String> permissions) {
    }

    public record Csrf(String headerName, String parameterName, String token) {
    }
}
