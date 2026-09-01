package edu.chd.practice.rmi.contract.dto;

import edu.chd.practice.rmi.contract.CanonicalForms;
import edu.chd.practice.rmi.contract.Canonicalizable;

import java.util.List;
import java.util.Objects;

public final class TransactionRequest implements Canonicalizable {
    private static final long serialVersionUID = 1L;

    private final String idempotencyKey;
    private final String semanticFingerprint;
    private final List<MutationCommand> commands;

    public TransactionRequest(String idempotencyKey, List<MutationCommand> commands) {
        this(idempotencyKey, null, commands);
    }

    public TransactionRequest(String idempotencyKey, String semanticFingerprint,
                              List<MutationCommand> commands) {
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.semanticFingerprint = semanticFingerprint;
        this.commands = commands == null ? List.of() : List.copyOf(commands);
    }

    public String getIdempotencyKey() { return idempotencyKey; }
    public String getSemanticFingerprint() { return semanticFingerprint; }
    public List<MutationCommand> getCommands() { return commands; }

    @Override
    public String canonicalForm() {
        return CanonicalForms.value(idempotencyKey) + CanonicalForms.value(semanticFingerprint)
                + CanonicalForms.collection(commands);
    }
}
