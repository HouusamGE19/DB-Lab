package com.example.dbperformancelab.service;

import com.example.dbperformancelab.dto.SqlRunRequest;
import com.example.dbperformancelab.dto.SqlRunResult;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;

@Service
public class QueryCacheService {
    private static final String PREFIX = "lab:sql:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final QueryService queryService;

    public QueryCacheService(StringRedisTemplate redis, ObjectMapper objectMapper, QueryService queryService) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.queryService = queryService;
    }

    public SqlRunResult run(SqlRunRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        String key = cacheKey(request.sql(), request.safeMaxRows(), request.shouldExplain());
        long started = System.nanoTime();
        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            try {
                SqlRunResult result = objectMapper.readValue(cached, SqlRunResult.class);
                return result.withSourceAndElapsed("REDIS_HIT", roundMs(System.nanoTime() - started));
            } catch (Exception ex) {
                redis.delete(key);
            }
        }

        SqlRunResult result = queryService.run(request).withSourceAndElapsed("REDIS_MISS_POSTGRES", roundMs(System.nanoTime() - started));
        try {
            redis.opsForValue().set(
                    key,
                    objectMapper.writeValueAsString(result),
                    Duration.ofSeconds(request.safeCacheTtlSeconds())
            );
        } catch (Exception ex) {
            throw new IllegalStateException("Query succeeded but Redis serialization failed: " + ex.getMessage(), ex);
        }
        return result;
    }

    public long clearAll() {
        Set<String> keys = redis.keys(PREFIX + "*");
        if (keys == null || keys.isEmpty()) {
            return 0;
        }
        Long deleted = redis.delete(keys);
        return deleted == null ? 0 : deleted;
    }

    private String cacheKey(String sql, int maxRows, boolean explain) {
        String normalized = (sql == null ? "" : sql.trim().replaceAll("\\s+", " ")) + "|" + maxRows + "|" + explain;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return PREFIX + HexFormat.of().formatHex(digest.digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
        }
    }

    private double roundMs(long nanos) {
        return Math.round((nanos / 1_000_000.0) * 1000.0) / 1000.0;
    }
}
