package com.example.dbperformancelab.dto;

import java.util.Map;

public record SeedResult(Map<String, Long> counts, double elapsedMs) {
}
