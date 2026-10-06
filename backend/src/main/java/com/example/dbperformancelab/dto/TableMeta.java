package com.example.dbperformancelab.dto;

import java.util.List;

public record TableMeta(String name, long estimatedRows, List<ColumnMeta> columns) {
}
