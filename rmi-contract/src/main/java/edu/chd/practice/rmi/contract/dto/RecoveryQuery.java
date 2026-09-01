package edu.chd.practice.rmi.contract.dto;

import edu.chd.practice.rmi.contract.CanonicalForms;
import edu.chd.practice.rmi.contract.Canonicalizable;

import java.util.Objects;

public final class RecoveryQuery implements Canonicalizable {
    private static final long serialVersionUID = 1L;

    private final String aggregateType;
    private final String aggregateId;
    private final int limit;

    public RecoveryQuery(String aggregateType, String aggregateId, int limit) {
        this.aggregateType = Objects.requireNonNull(aggregateType, "aggregateType");
        this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId");
        this.limit = limit;
    }

    public String getAggregateType() { return aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public int getLimit() { return limit; }

    @Override
    public String canonicalForm() {
        return CanonicalForms.value(aggregateType) + CanonicalForms.value(aggregateId)
                + CanonicalForms.value(limit);
    }
}
