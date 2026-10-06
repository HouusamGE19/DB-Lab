package com.example.dbperformancelab.dto;

public record TableStats(
        String table,
        long liveRows,
        long sequentialScans,
        long indexScans,
        long inserted,
        long updated,
        long deleted
) {
}
