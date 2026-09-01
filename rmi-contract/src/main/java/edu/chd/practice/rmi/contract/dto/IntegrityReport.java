package edu.chd.practice.rmi.contract.dto;

import java.io.Serializable;

public final class IntegrityReport implements Serializable {
    private static final long serialVersionUID = 1L;

    private final boolean valid;
    private final long checkedEntries;
    private final Long firstInvalidSequence;
    private final String message;

    public IntegrityReport(boolean valid, long checkedEntries, Long firstInvalidSequence, String message) {
        this.valid = valid;
        this.checkedEntries = checkedEntries;
        this.firstInvalidSequence = firstInvalidSequence;
        this.message = message;
    }

    public boolean isValid() { return valid; }
    public long getCheckedEntries() { return checkedEntries; }
    public Long getFirstInvalidSequence() { return firstInvalidSequence; }
    public String getMessage() { return message; }
}
