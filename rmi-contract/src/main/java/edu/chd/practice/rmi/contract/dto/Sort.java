package edu.chd.practice.rmi.contract.dto;

import edu.chd.practice.rmi.contract.CanonicalForms;
import edu.chd.practice.rmi.contract.Canonicalizable;

import java.util.Objects;

public final class Sort implements Canonicalizable {
    private static final long serialVersionUID = 1L;

    private final String column;
    private final SortDirection direction;

    public Sort(String column, SortDirection direction) {
        this.column = Objects.requireNonNull(column, "column");
        this.direction = Objects.requireNonNull(direction, "direction");
    }

    public String getColumn() { return column; }
    public SortDirection getDirection() { return direction; }

    @Override
    public String canonicalForm() {
        return CanonicalForms.value(column) + CanonicalForms.value(direction.name());
    }
}
