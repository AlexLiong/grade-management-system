package edu.chd.practice.rmi.contract.dto;

import edu.chd.practice.rmi.contract.CanonicalForms;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

public final class InvocationContext implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String requestId;
    private final String principal;
    private final List<String> roles;
    private final long timestampEpochMillis;
    private final String nonce;
    private final String signature;

    public InvocationContext(String requestId, String principal, List<String> roles,
                             long timestampEpochMillis, String nonce, String signature) {
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.roles = roles == null ? List.of() : roles.stream().sorted().toList();
        this.timestampEpochMillis = timestampEpochMillis;
        this.nonce = Objects.requireNonNull(nonce, "nonce");
        this.signature = signature;
    }

    public InvocationContext withSignature(String value) {
        return new InvocationContext(requestId, principal, roles, timestampEpochMillis, nonce, value);
    }

    public String getRequestId() { return requestId; }
    public String getPrincipal() { return principal; }
    public List<String> getRoles() { return roles; }
    public long getTimestampEpochMillis() { return timestampEpochMillis; }
    public String getNonce() { return nonce; }
    public String getSignature() { return signature; }

    public String canonicalIdentity() {
        return CanonicalForms.value(requestId) + CanonicalForms.value(principal)
                + CanonicalForms.collection(roles) + CanonicalForms.value(timestampEpochMillis)
                + CanonicalForms.value(nonce);
    }
}
