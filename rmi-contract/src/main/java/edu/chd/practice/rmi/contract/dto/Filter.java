package edu.chd.practice.rmi.contract.dto;

import edu.chd.practice.rmi.contract.CanonicalForms;
import edu.chd.practice.rmi.contract.Canonicalizable;

import java.util.List;
import java.util.Objects;

public final class Filter implements Canonicalizable {
    private static final long serialVersionUID = 1L;

    private final String column;
    private final FilterOperator operator;
    private final List<String> values;

    public Filter(String column, FilterOperator operator, List<String> values) {
        this.column = Objects.requireNonNull(column, "column");
        this.operator = Objects.requireNonNull(operator, "operator");
        this.values = values == null ? List.of() : List.copyOf(values);
    }

    public static Filter of(String column, FilterOperator operator, String... values) {
        return new Filter(column, operator, values == null ? List.of() : List.of(values));
    }

    public String getColumn() { return column; }
    public FilterOperator getOperator() { return operator; }
    public List<String> getValues() { return values; }

    @Override
    public String canonicalForm() {
        return CanonicalForms.value(column) + CanonicalForms.value(operator.name())
                + CanonicalForms.collection(values);
    }
}
