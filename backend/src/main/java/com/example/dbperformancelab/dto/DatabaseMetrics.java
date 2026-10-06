package com.example.dbperformancelab.dto;

import java.util.List;

public record DatabaseMetrics(
        long databaseBytes,
        int activeConnections,
        long blocksRead,
        long blocksHit,
        double cacheHitRatio,
        long commits,
        long rollbacks,
        List<TableStats> tables
) {
}
