package edu.chd.practice.rmi.contract.dto;

import edu.chd.practice.rmi.contract.CanonicalForms;
import edu.chd.practice.rmi.contract.Canonicalizable;

public final class RecoveryEvidenceRequest implements Canonicalizable {
    private static final long serialVersionUID = 1L;

    private final long sequence;
    private final String reason;

    public RecoveryEvidenceRequest(long sequence, String reason) {
        this.sequence = sequence;
        this.reason = reason;
    }

    public long getSequence() { return sequence; }
    public String getReason() { return reason; }

    @Override
    public String canonicalForm() {
        return CanonicalForms.value(sequence) + CanonicalForms.value(reason);
    }
}
