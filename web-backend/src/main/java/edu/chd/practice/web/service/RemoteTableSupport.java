package edu.chd.practice.web.service;

import edu.chd.practice.rmi.contract.dto.Filter;
import edu.chd.practice.rmi.contract.dto.SelectRequest;
import edu.chd.practice.rmi.contract.dto.Sort;
import edu.chd.practice.web.api.PageResult;
import edu.chd.practice.web.error.ApiException;
import edu.chd.practice.web.rmi.RemoteDataGateway;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public abstract class RemoteTableSupport {
    protected final RemoteDataGateway gateway;

    protected RemoteTableSupport(RemoteDataGateway gateway) {
        this.gateway = gateway;
    }

    protected List<Map<String, String>> rows(String table, List<String> columns, List<Filter> filters,
                                             List<Sort> sorts, int page, int size) {
        return gateway.select(new SelectRequest(table, columns, filters, sorts, page, Math.min(size, 500)));
    }

    protected Map<String, String> one(String table, List<String> columns, List<Filter> filters) {
        List<Map<String, String>> rows = rows(table, columns, filters, List.of(), 0, 1);
        return rows.isEmpty() ? null : rows.get(0);
    }

    protected Map<String, String> requireOne(String table, List<String> columns, List<Filter> filters,
                                             String code, String message) {
        Map<String, String> row = one(table, columns, filters);
        if (row == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, code, message);
        }
        return row;
    }

    protected long count(String table, List<Filter> filters) {
        return gateway.count(new SelectRequest(table, List.of("id"), filters, List.of(), 0, 1));
    }

    protected <T> PageResult<T> page(List<T> items, int page, int size, long total) {
        return PageResult.of(items, page, size, total);
    }

    protected int integer(Map<String, String> row, String key) {
        String value = row.get(key);
        return value == null || value.isBlank() ? 0 : Integer.parseInt(value);
    }

    protected long longValue(Map<String, String> row, String key) {
        String value = row.get(key);
        return value == null || value.isBlank() ? 0 : Long.parseLong(value);
    }

    protected BigDecimal decimal(Map<String, String> row, String key) {
        String value = row.get(key);
        return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value);
    }

    protected Instant instant(Map<String, String> row, String key) {
        String value = row.get(key);
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }
}
