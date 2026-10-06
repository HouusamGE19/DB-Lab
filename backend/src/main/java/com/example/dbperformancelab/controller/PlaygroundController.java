package com.example.dbperformancelab.controller;

import com.example.dbperformancelab.dto.DatabaseMetrics;
import com.example.dbperformancelab.dto.IndexCreateRequest;
import com.example.dbperformancelab.dto.IndexInfo;
import com.example.dbperformancelab.dto.SeedRequest;
import com.example.dbperformancelab.dto.SeedResult;
import com.example.dbperformancelab.dto.SqlRunRequest;
import com.example.dbperformancelab.dto.SqlRunResult;
import com.example.dbperformancelab.dto.TableMeta;
import com.example.dbperformancelab.service.IndexService;
import com.example.dbperformancelab.service.MetadataService;
import com.example.dbperformancelab.service.QueryCacheService;
import com.example.dbperformancelab.service.QueryService;
import com.example.dbperformancelab.service.SeedService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/lab")
public class PlaygroundController {
    private final SeedService seedService;
    private final MetadataService metadataService;
    private final QueryService queryService;
    private final QueryCacheService queryCacheService;
    private final IndexService indexService;

    public PlaygroundController(
            SeedService seedService,
            MetadataService metadataService,
            QueryService queryService,
            QueryCacheService queryCacheService,
            IndexService indexService
    ) {
        this.seedService = seedService;
        this.metadataService = metadataService;
        this.queryService = queryService;
        this.queryCacheService = queryCacheService;
        this.indexService = indexService;
    }

    @PostMapping("/seed")
    public SeedResult seed(@RequestBody SeedRequest request) {
        queryCacheService.clearAll();
        return seedService.seed(request);
    }

    @GetMapping("/schema")
    public List<TableMeta> schema() {
        return metadataService.schema();
    }

    @GetMapping("/counts")
    public Map<String, Long> counts() {
        return metadataService.exactCounts();
    }

    @GetMapping("/metrics")
    public DatabaseMetrics metrics() {
        return metadataService.metrics();
    }

    @PostMapping("/sql/run")
    public SqlRunResult runSql(@RequestBody SqlRunRequest request) {
        return queryService.run(request);
    }

    @PostMapping("/sql/cached")
    public SqlRunResult runCachedSql(@RequestBody SqlRunRequest request) {
        return queryCacheService.run(request);
    }

    @DeleteMapping("/sql/cache")
    public Map<String, Long> clearSqlCache() {
        return Map.of("deleted", queryCacheService.clearAll());
    }

    @GetMapping("/indexes")
    public List<IndexInfo> indexes() {
        return indexService.list();
    }

    @PostMapping("/indexes")
    public IndexInfo createIndex(@RequestBody IndexCreateRequest request) {
        return indexService.create(request);
    }

    @DeleteMapping("/indexes/{name}")
    public ResponseEntity<Void> dropIndex(@PathVariable String name) {
        indexService.drop(name);
        return ResponseEntity.noContent().build();
    }
}
