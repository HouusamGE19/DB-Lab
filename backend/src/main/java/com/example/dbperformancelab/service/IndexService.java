package com.example.dbperformancelab.service;

import com.example.dbperformancelab.dto.IndexColumn;
import com.example.dbperformancelab.dto.IndexCreateRequest;
import com.example.dbperformancelab.dto.IndexInfo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class IndexService {
    private static final Set<String> METHODS = Set.of("BTREE", "HASH", "GIN", "GIST", "BRIN");
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]{0,62}");
    private static final String MANAGED_PREFIX = "lab_idx_";

    private final JdbcTemplate jdbc;
    private final MetadataService metadataService;

    public IndexService(JdbcTemplate jdbc, MetadataService metadataService) {
        this.jdbc = jdbc;
        this.metadataService = metadataService;
    }

    public IndexInfo create(IndexCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Index request cannot be empty.");
        }

        String table = normalizeIdentifier(request.table(), "table");
        if (!metadataService.tableExists(table)) {
            throw new IllegalArgumentException("Unknown lab table: " + table);
        }

        String method = request.method() == null ? "BTREE" : request.method().trim().toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) {
            throw new IllegalArgumentException("Unsupported index method: " + method);
        }

        List<IndexColumn> columns = request.columns() == null ? List.of() : request.columns();
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("Choose at least one index column.");
        }
        if (method.equals("HASH") && columns.size() != 1) {
            throw new IllegalArgumentException("PostgreSQL HASH indexes in this lab are limited to one column.");
        }

        List<String> columnSql = new ArrayList<>();
        for (IndexColumn column : columns) {
            String name = normalizeIdentifier(column.name(), "column");
            if (!metadataService.columnExists(table, name)) {
                throw new IllegalArgumentException("Column " + name + " does not exist on " + table + ".");
            }
            String direction = column.direction() == null ? "ASC" : column.direction().trim().toUpperCase(Locale.ROOT);
            if (!Set.of("ASC", "DESC").contains(direction)) {
                throw new IllegalArgumentException("Direction must be ASC or DESC.");
            }
            if (!method.equals("BTREE")) {
                direction = "";
            }
            columnSql.add(quote(name) + (direction.isBlank() ? "" : " " + direction));
        }

        List<String> includes = request.includeColumns() == null ? List.of() : request.includeColumns();
        if (!includes.isEmpty() && !method.equals("BTREE")) {
            throw new IllegalArgumentException("INCLUDE columns are enabled only for BTREE indexes in this lab.");
        }
        List<String> includeSql = new ArrayList<>();
        for (String include : includes) {
            String name = normalizeIdentifier(include, "include column");
            if (!metadataService.columnExists(table, name)) {
                throw new IllegalArgumentException("Column " + name + " does not exist on " + table + ".");
            }
            includeSql.add(quote(name));
        }

        String requestedName = request.name() == null ? "" : request.name().trim();
        String name;
        if (requestedName.isBlank()) {
            name = generatedName(table, method);
        } else {
            String normalized = normalizeIdentifier(requestedName, "index name");
            name = normalized.startsWith(MANAGED_PREFIX) ? normalized : MANAGED_PREFIX + normalized;
            if (name.length() > 63) {
                name = name.substring(0, 63);
            }
        }

        StringBuilder sql = new StringBuilder("CREATE INDEX ");
        if (request.concurrent()) {
            sql.append("CONCURRENTLY ");
        }
        sql.append(quote(name))
                .append(" ON ").append(quote(table))
                .append(" USING ").append(method)
                .append(" (").append(String.join(", ", columnSql)).append(")");
        if (!includeSql.isEmpty()) {
            sql.append(" INCLUDE (").append(String.join(", ", includeSql)).append(")");
        }

        jdbc.execute(sql.toString());
        jdbc.execute("ANALYZE " + quote(table));
        final String createdName = name;
        return list().stream()
                .filter(index -> index.name().equals(createdName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Index was created but could not be read back."));
    }

    public List<IndexInfo> list() {
        return jdbc.query("""
                SELECT
                    i.relname AS index_name,
                    t.relname AS table_name,
                    pg_get_indexdef(i.oid) AS definition,
                    pg_relation_size(i.oid) AS size_bytes,
                    COALESCE(s.idx_scan, 0) AS scans
                FROM pg_class i
                JOIN pg_index x ON x.indexrelid = i.oid
                JOIN pg_class t ON t.oid = x.indrelid
                LEFT JOIN pg_stat_user_indexes s ON s.indexrelid = i.oid
                WHERE t.relname IN ('users','categories','products','inventory','orders','order_items','reviews')
                ORDER BY t.relname, i.relname
                """, (rs, rowNum) -> {
            String name = rs.getString("index_name");
            return new IndexInfo(
                    name,
                    rs.getString("table_name"),
                    rs.getString("definition"),
                    rs.getLong("size_bytes"),
                    rs.getLong("scans"),
                    name.startsWith(MANAGED_PREFIX)
            );
        });
    }

    public void drop(String rawName) {
        String name = normalizeIdentifier(rawName, "index name");
        if (!name.startsWith(MANAGED_PREFIX)) {
            throw new IllegalArgumentException("Only indexes created by the lab can be dropped here.");
        }
        jdbc.execute("DROP INDEX IF EXISTS " + quote(name));
    }

    public void dropAllManagedIndexes() {
        List<String> managed = list().stream().filter(IndexInfo::managed).map(IndexInfo::name).toList();
        for (String index : managed) {
            jdbc.execute("DROP INDEX IF EXISTS " + quote(index));
        }
    }

    private String generatedName(String table, String method) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        String base = MANAGED_PREFIX + table + "_" + method.toLowerCase(Locale.ROOT) + "_" + suffix;
        return base.length() <= 63 ? base : base.substring(0, 63);
    }

    private String normalizeIdentifier(String value, String field) {
        if (value == null || !SAFE_IDENTIFIER.matcher(value.trim()).matches()) {
            throw new IllegalArgumentException("Invalid " + field + ". Use letters, digits and underscores only.");
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
