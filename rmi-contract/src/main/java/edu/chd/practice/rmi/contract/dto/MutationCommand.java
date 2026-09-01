package edu.chd.practice.rmi.contract.dto;

import edu.chd.practice.rmi.contract.CanonicalForms;
import edu.chd.practice.rmi.contract.Canonicalizable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.Collections;

public final class MutationCommand implements Canonicalizable {
    private static final long serialVersionUID = 1L;

    private final MutationType type;
    private final String table;
    private final Map<String, String> values;
    private final List<Filter> filters;
    private final String reason;
    private final String approvalId;

    public MutationCommand(MutationType type, String table, Map<String, String> values,
                           List<Filter> filters) {
        this(type, table, values, filters, null, null);
    }

    public MutationCommand(MutationType type, String table, Map<String, String> values,
                           List<Filter> filters, String reason, String approvalId) {
        this.type = Objects.requireNonNull(type, "type");
        this.table = Objects.requireNonNull(table, "table");
        this.values = values == null ? Map.of()
                : Collections.unmodifiableMap(new TreeMap<>(values));
        this.filters = filters == null ? List.of() : List.copyOf(filters);
        this.reason = reason;
        this.approvalId = approvalId;
    }

    public MutationType getType() { return type; }
    public String getTable() { return table; }
    public Map<String, String> getValues() { return values; }
    public List<Filter> getFilters() { return filters; }
    public String getReason() { return reason; }
    public String getApprovalId() { return approvalId; }

    @Override
    public String canonicalForm() {
        return CanonicalForms.value(type.name()) + CanonicalForms.value(table)
                + CanonicalForms.map(values) + CanonicalForms.collection(filters)
                + CanonicalForms.value(reason) + CanonicalForms.value(approvalId);
    }
}
