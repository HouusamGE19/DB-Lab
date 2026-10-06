package com.example.dbperformancelab.service;

import com.example.dbperformancelab.dto.ColumnMeta;
import com.example.dbperformancelab.dto.DatabaseMetrics;
import com.example.dbperformancelab.dto.TableMeta;
import com.example.dbperformancelab.dto.TableStats;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MetadataService {
    public static final List<String> LAB_TABLES = List.of(
            "users", "categories", "products", "inventory", "orders", "order_items", "reviews"
    );

    private final JdbcTemplate jdbc;

    public MetadataService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<TableMeta> schema() {
        Map<String, List<ColumnMeta>> columns = new LinkedHashMap<>();
        for (String table : LAB_TABLES) {
            columns.put(table, new ArrayList<>());
        }

        jdbc.query("""
                SELECT table_name, column_name, data_type
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name IN ('users','categories','products','inventory','orders','order_items','reviews')
                ORDER BY table_name, ordinal_position
                """, rs -> {
            List<ColumnMeta> target = columns.get(rs.getString("table_name"));
            if (target != null) {
                target.add(new ColumnMeta(rs.getString("column_name"), rs.getString("data_type")));
            }
        });

        Map<String, Long> estimates = new LinkedHashMap<>();
        jdbc.query("""
                SELECT relname, GREATEST(reltuples, 0)::bigint AS estimated_rows
                FROM pg_class
                WHERE relnamespace = 'public'::regnamespace
                  AND relname IN ('users','categories','products','inventory','orders','order_items','reviews')
                """, rs -> {
            estimates.put(rs.getString("relname"), rs.getLong("estimated_rows"));
        });

        return LAB_TABLES.stream()
                .map(table -> new TableMeta(table, estimates.getOrDefault(table, 0L), columns.getOrDefault(table, List.of())))
                .toList();
    }

    public Map<String, Long> exactCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : LAB_TABLES) {
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
            counts.put(table, count == null ? 0L : count);
        }
        return counts;
    }

    public DatabaseMetrics metrics() {
        DatabaseRow database = jdbc.queryForObject("""
                SELECT
                    pg_database_size(current_database()) AS database_bytes,
                    numbackends,
                    blks_read,
                    blks_hit,
                    xact_commit,
                    xact_rollback
                FROM pg_stat_database
                WHERE datname = current_database()
                """, (rs, rowNum) -> new DatabaseRow(
                rs.getLong("database_bytes"),
                rs.getInt("numbackends"),
                rs.getLong("blks_read"),
                rs.getLong("blks_hit"),
                rs.getLong("xact_commit"),
                rs.getLong("xact_rollback")
        ));

        if (database == null) {
            database = new DatabaseRow(0, 0, 0, 0, 0, 0);
        }

        List<TableStats> tables = jdbc.query("""
                SELECT
                    relname,
                    n_live_tup,
                    seq_scan,
                    idx_scan,
                    n_tup_ins,
                    n_tup_upd,
                    n_tup_del
                FROM pg_stat_user_tables
                WHERE relname IN ('users','categories','products','inventory','orders','order_items','reviews')
                ORDER BY relname
                """, (rs, rowNum) -> new TableStats(
                rs.getString("relname"),
                rs.getLong("n_live_tup"),
                rs.getLong("seq_scan"),
                rs.getLong("idx_scan"),
                rs.getLong("n_tup_ins"),
                rs.getLong("n_tup_upd"),
                rs.getLong("n_tup_del")
        ));

        long totalBlocks = database.blocksHit + database.blocksRead;
        double hitRatio = totalBlocks == 0 ? 0 : Math.round((database.blocksHit * 10000.0 / totalBlocks)) / 100.0;
        return new DatabaseMetrics(
                database.databaseBytes,
                database.activeConnections,
                database.blocksRead,
                database.blocksHit,
                hitRatio,
                database.commits,
                database.rollbacks,
                tables
        );
    }

    public boolean tableExists(String table) {
        return LAB_TABLES.contains(table);
    }

    public boolean columnExists(String table, String column) {
        if (!tableExists(table) || column == null) {
            return false;
        }
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                """, Integer.class, table, column);
        return count != null && count > 0;
    }

    private record DatabaseRow(
            long databaseBytes,
            int activeConnections,
            long blocksRead,
            long blocksHit,
            long commits,
            long rollbacks
    ) {
    }
}
