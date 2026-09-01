package edu.chd.practice.rmi.contract.dto;

import edu.chd.practice.rmi.contract.CanonicalForms;
import edu.chd.practice.rmi.contract.Canonicalizable;

public final class RecoveryRestoreRequest implements Canonicalizable {
    private static final long serialVersionUID = 1L;

    private final long sequence;
    private final String reason;
    private final String approvalId;

    public RecoveryRestoreRequest(long sequence, String reason, String approvalId) {
        this.sequence = sequence;
        this.reason = reason;
        this.approvalId = approvalId;
    }

    public long getSequence() { return sequence; }
    public String getReason() { return reason; }
    public String getApprovalId() { return approvalId; }

    @Override
    public String canonicalForm() {
        return CanonicalForms.value(sequence) + CanonicalForms.value(reason)
                + CanonicalForms.value(approvalId);
    }
}
