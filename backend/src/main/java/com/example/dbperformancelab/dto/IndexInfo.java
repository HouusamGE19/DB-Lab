package com.example.dbperformancelab.dto;

public record IndexInfo(
        String name,
        String table,
        String definition,
        long sizeBytes,
        long scans,
        boolean managed
) {
}
