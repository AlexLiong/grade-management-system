package edu.chd.practice.rmi.server.sql;

import edu.chd.practice.rmi.contract.dto.Filter;
import edu.chd.practice.rmi.contract.dto.FilterOperator;
import edu.chd.practice.rmi.contract.dto.MutationCommand;
import edu.chd.practice.rmi.contract.dto.MutationType;
import edu.chd.practice.rmi.contract.dto.SelectRequest;
import edu.chd.practice.rmi.contract.dto.Sort;
import edu.chd.practice.rmi.server.config.SecurityProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class SafeSqlBuilder {
    private static final int MAX_FILTERS = 30;
    private static final int MAX_IN_VALUES = 500;

    private final SchemaRegistry registry;
    private final SecurityProperties properties;

    public SafeSqlBuilder(SchemaRegistry registry, SecurityProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    public PreparedSql select(SelectRequest request, DatabaseDialect dialect) {
        requirePage(request);
        SchemaRegistry.TableDefinition table = registry.requireTable(request.getTable());
        List<String> columns = selectedColumns(request, table);
        List<Object> parameters = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT ").append(String.join(", ", columns))
                .append(" FROM ").append(table.name());
        appendFilters(sql, parameters, request.getFilters(), table);
        appendSorts(sql, request.getSorts(), table);
        dialect.appendPage(sql, parameters, Math.multiplyExact((long) request.getPage(), request.getPageSize()),
                request.getPageSize());
        return new PreparedSql(sql.toString(), parameters, columns);
    }

    public PreparedSql count(SelectRequest request) {
        SchemaRegistry.TableDefinition table = registry.requireTable(request.getTable());
        List<Object> parameters = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ").append(table.name());
        appendFilters(sql, parameters, request.getFilters(), table);
        return new PreparedSql(sql.toString(), parameters, List.of());
    }

    public PreparedSql snapshot(String tableName, List<String> columnNames, List<Filter> filters) {
        SchemaRegistry.TableDefinition table = registry.requireTable(tableName);
        List<String> columns = columnNames.stream().peek(table::requireColumn).distinct().toList();
        if (columns.isEmpty()) throw new IllegalArgumentException("Snapshot columns cannot be empty");
        List<Object> parameters = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT ").append(String.join(", ", columns))
                .append(" FROM ").append(table.name());
        appendFilters(sql, parameters, filters, table);
        sql.append(" ORDER BY ").append(table.columns().containsKey("id") ? "id" : columns.get(0));
        return new PreparedSql(sql.toString(), parameters, columns);
    }

    public PreparedSql mutation(MutationCommand command) {
        SchemaRegistry.TableDefinition table = registry.requireTable(command.getTable());
        if (command.getType() == MutationType.INSERT) return insert(command, table);
        if (command.getFilters().isEmpty()) {
            throw new IllegalArgumentException("UPDATE and DELETE require at least one filter");
        }
        return command.getType() == MutationType.UPDATE ? update(command, table) : delete(command, table);
    }

    private PreparedSql insert(MutationCommand command, SchemaRegistry.TableDefinition table) {
        if (command.getValues().isEmpty()) throw new IllegalArgumentException("INSERT values cannot be empty");
        List<String> columns = new ArrayList<>();
        List<Object> parameters = new ArrayList<>();
        command.getValues().forEach((name, value) -> {
            SchemaRegistry.ColumnDefinition column = table.requireColumn(name);
            columns.add(column.name());
            parameters.add(convert(column, value));
        });
        String placeholders = String.join(", ", columns.stream().map(ignored -> "?").toList());
        String sql = "INSERT INTO " + table.name() + " (" + String.join(", ", columns)
                + ") VALUES (" + placeholders + ")";
        return new PreparedSql(sql, parameters, List.of());
    }

    private PreparedSql update(MutationCommand command, SchemaRegistry.TableDefinition table) {
        if (command.getValues().isEmpty()) throw new IllegalArgumentException("UPDATE values cannot be empty");
        List<Object> parameters = new ArrayList<>();
        List<String> assignments = new ArrayList<>();
        command.getValues().forEach((name, value) -> {
            SchemaRegistry.ColumnDefinition column = table.requireColumn(name);
            assignments.add(column.name() + " = ?");
            parameters.add(convert(column, value));
        });
        StringBuilder sql = new StringBuilder("UPDATE ").append(table.name()).append(" SET ")
                .append(String.join(", ", assignments));
        appendFilters(sql, parameters, command.getFilters(), table);
        return new PreparedSql(sql.toString(), parameters, List.of());
    }

    private PreparedSql delete(MutationCommand command, SchemaRegistry.TableDefinition table) {
        if (!command.getValues().isEmpty()) throw new IllegalArgumentException("DELETE cannot contain values");
        List<Object> parameters = new ArrayList<>();
        StringBuilder sql = new StringBuilder("DELETE FROM ").append(table.name());
        appendFilters(sql, parameters, command.getFilters(), table);
        return new PreparedSql(sql.toString(), parameters, List.of());
    }

    private List<String> selectedColumns(SelectRequest request, SchemaRegistry.TableDefinition table) {
        List<String> requested = request.getColumns().isEmpty()
                ? new ArrayList<>(table.columns().keySet()) : request.getColumns();
        if (requested.size() > table.columns().size()) throw new IllegalArgumentException("Too many selected columns");
        return requested.stream().peek(table::requireColumn).distinct().toList();
    }

    private void appendSorts(StringBuilder sql, List<Sort> sorts, SchemaRegistry.TableDefinition table) {
        List<String> expressions = new ArrayList<>();
        for (Sort sort : sorts) {
            table.requireColumn(sort.getColumn());
            expressions.add(sort.getColumn() + " " + sort.getDirection().name());
        }
        if (expressions.isEmpty()) {
            String stableColumn = table.columns().containsKey("id") ? "id" : table.columns().keySet().iterator().next();
            expressions.add(stableColumn + " ASC");
        }
        sql.append(" ORDER BY ").append(String.join(", ", expressions));
    }

    private void appendFilters(StringBuilder sql, List<Object> parameters, List<Filter> filters,
                               SchemaRegistry.TableDefinition table) {
        if (filters.size() > MAX_FILTERS) throw new IllegalArgumentException("Too many filters");
        List<String> predicates = new ArrayList<>();
        for (Filter filter : filters) {
            SchemaRegistry.ColumnDefinition column = table.requireColumn(filter.getColumn());
            predicates.add(predicate(column, filter, parameters));
        }
        if (!predicates.isEmpty()) sql.append(" WHERE ").append(String.join(" AND ", predicates));
    }

    private String predicate(SchemaRegistry.ColumnDefinition column, Filter filter, List<Object> parameters) {
        List<String> values = filter.getValues();
        FilterOperator operator = filter.getOperator();
        return switch (operator) {
            case IS_NULL -> requireCount(column.name() + " IS NULL", values, 0);
            case IS_NOT_NULL -> requireCount(column.name() + " IS NOT NULL", values, 0);
            case EQ -> scalar(column, " = ?", values, parameters);
            case NE -> scalar(column, " <> ?", values, parameters);
            case GT -> scalar(column, " > ?", values, parameters);
            case GE -> scalar(column, " >= ?", values, parameters);
            case LT -> scalar(column, " < ?", values, parameters);
            case LE -> scalar(column, " <= ?", values, parameters);
            case LIKE -> scalar(column, " LIKE ?", values, parameters);
            case CONTAINS -> literalContains(column, values, parameters);
            case BETWEEN -> {
                requireCount("", values, 2);
                parameters.add(convert(column, values.get(0)));
                parameters.add(convert(column, values.get(1)));
                yield column.name() + " BETWEEN ? AND ?";
            }
            case IN -> {
                if (values.isEmpty() || values.size() > MAX_IN_VALUES) {
                    throw new IllegalArgumentException("IN requires 1 to " + MAX_IN_VALUES + " values");
                }
                values.forEach(value -> parameters.add(convert(column, value)));
                yield column.name() + " IN (" + String.join(", ", values.stream().map(ignored -> "?").toList()) + ")";
            }
        };
    }

    private String literalContains(SchemaRegistry.ColumnDefinition column, List<String> values,
                                   List<Object> parameters) {
        requireCount("", values, 1);
        if (column.type() != ColumnType.STRING) {
            throw new IllegalArgumentException("CONTAINS is only valid for string columns");
        }
        String escaped = values.get(0).replace("!", "!!").replace("%", "!%").replace("_", "!_");
        parameters.add("%" + escaped + "%");
        return column.name() + " LIKE ? ESCAPE '!'";
    }

    private String scalar(SchemaRegistry.ColumnDefinition column, String operator, List<String> values,
                          List<Object> parameters) {
        requireCount("", values, 1);
        parameters.add(convert(column, values.get(0)));
        return column.name() + operator;
    }

    private static String requireCount(String expression, List<String> values, int expected) {
        if (values.size() != expected) throw new IllegalArgumentException("Filter expects " + expected + " value(s)");
        return expression;
    }

    private static Object convert(SchemaRegistry.ColumnDefinition column, String value) {
        try {
            return column.type().convert(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid value for " + column.name(), exception);
        }
    }

    private void requirePage(SelectRequest request) {
        if (request.getPage() < 0 || request.getPageSize() < 1
                || request.getPageSize() > properties.getMaxPageSize()) {
            throw new IllegalArgumentException("Page must be >= 0 and pageSize must be between 1 and "
                    + properties.getMaxPageSize());
        }
    }
}
