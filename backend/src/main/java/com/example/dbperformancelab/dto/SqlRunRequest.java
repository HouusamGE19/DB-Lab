package com.example.dbperformancelab.dto;

public record SqlRunRequest(String sql, Integer maxRows, Boolean explainAnalyze, Integer cacheTtlSeconds) {
    public int safeMaxRows() {
        int value = maxRows == null ? 100 : maxRows;
        return Math.max(1, Math.min(value, 500));
    }

    public boolean shouldExplain() {
        return explainAnalyze == null || explainAnalyze;
    }

    public int safeCacheTtlSeconds() {
        int value = cacheTtlSeconds == null ? 60 : cacheTtlSeconds;
        return Math.max(5, Math.min(value, 3600));
    }
}
