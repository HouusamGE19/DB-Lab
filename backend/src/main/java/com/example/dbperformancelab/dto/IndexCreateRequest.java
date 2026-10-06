package com.example.dbperformancelab.dto;

import java.util.List;

public record IndexCreateRequest(
        String name,
        String table,
        String method,
        List<IndexColumn> columns,
        List<String> includeColumns,
        Boolean concurrently
) {
    public boolean concurrent() {
        return Boolean.TRUE.equals(concurrently);
    }
}
