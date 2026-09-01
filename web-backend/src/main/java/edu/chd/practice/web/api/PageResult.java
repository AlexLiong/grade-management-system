package edu.chd.practice.web.api;

import java.util.List;

public record PageResult<T>(List<T> items, int page, int size, long total, int totalPages) {
    public PageResult {
        items = items == null ? List.of() : List.copyOf(items);
        if (page < 0 || size < 1 || total < 0) {
            throw new IllegalArgumentException("Invalid page metadata");
        }
        totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / size);
    }

    public static <T> PageResult<T> of(List<T> items, int page, int size, long total) {
        return new PageResult<>(items, page, size, total, 0);
    }
}
