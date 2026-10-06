package com.example.dbperformancelab.dto;

import java.util.List;
import java.util.Map;

public record SqlRunResult(
        String source,
        double elapsedMs,
        Double planningTimeMs,
        Double executionTimeMs,
        int returnedRows,
        boolean truncated,
        List<String> columns,
        List<Map<String, Object>> rows,
        List<String> explainPlan
) {
    public SqlRunResult withSourceAndElapsed(String nextSource, double nextElapsedMs) {
        return new SqlRunResult(
                nextSource,
                nextElapsedMs,
                planningTimeMs,
                executionTimeMs,
                returnedRows,
                truncated,
                columns,
                rows,
                explainPlan
        );
    }
}
