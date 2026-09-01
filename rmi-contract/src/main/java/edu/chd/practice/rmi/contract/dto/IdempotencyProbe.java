package edu.chd.practice.rmi.contract.dto;

import edu.chd.practice.rmi.contract.CanonicalForms;
import edu.chd.practice.rmi.contract.Canonicalizable;

import java.util.Objects;

public final class IdempotencyProbe implements Canonicalizable {
    private static final long serialVersionUID = 1L;

    private final String idempotencyKey;
    private final String semanticFingerprint;

    public IdempotencyProbe(String idempotencyKey, String semanticFingerprint) {
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.semanticFingerprint = Objects.requireNonNull(semanticFingerprint, "semanticFingerprint");
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getSemanticFingerprint() {
        return semanticFingerprint;
    }

    @Override
    public String canonicalForm() {
        return CanonicalForms.value(idempotencyKey) + CanonicalForms.value(semanticFingerprint);
    }
}
