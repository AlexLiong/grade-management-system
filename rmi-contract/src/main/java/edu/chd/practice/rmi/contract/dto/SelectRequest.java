package edu.chd.practice.rmi.contract.dto;

import edu.chd.practice.rmi.contract.CanonicalForms;
import edu.chd.practice.rmi.contract.Canonicalizable;

import java.util.List;
import java.util.Objects;

public final class SelectRequest implements Canonicalizable {
    private static final long serialVersionUID = 1L;

    private final String table;
    private final List<String> columns;
    private final List<Filter> filters;
    private final List<Sort> sorts;
    private final int page;
    private final int pageSize;

    public SelectRequest(String table, List<String> columns, List<Filter> filters,
                         List<Sort> sorts, int page, int pageSize) {
        this.table = Objects.requireNonNull(table, "table");
        this.columns = columns == null ? List.of() : List.copyOf(columns);
        this.filters = filters == null ? List.of() : List.copyOf(filters);
        this.sorts = sorts == null ? List.of() : List.copyOf(sorts);
        this.page = page;
        this.pageSize = pageSize;
    }

    public String getTable() { return table; }
    public List<String> getColumns() { return columns; }
    public List<Filter> getFilters() { return filters; }
    public List<Sort> getSorts() { return sorts; }
    public int getPage() { return page; }
    public int getPageSize() { return pageSize; }

    @Override
    public String canonicalForm() {
        return CanonicalForms.value(table) + CanonicalForms.collection(columns)
                + CanonicalForms.collection(filters) + CanonicalForms.collection(sorts)
                + CanonicalForms.value(page) + CanonicalForms.value(pageSize);
    }
}
