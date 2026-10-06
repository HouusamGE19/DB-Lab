package com.example.dbperformancelab.service;

import com.example.dbperformancelab.dto.SqlRunRequest;
import com.example.dbperformancelab.dto.SqlRunResult;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class QueryService {
    private static final Pattern PLANNING_TIME = Pattern.compile("Planning Time: ([0-9.]+) ms");
    private static final Pattern EXECUTION_TIME = Pattern.compile("Execution Time: ([0-9.]+) ms");
    private static final int QUERY_TIMEOUT_SECONDS = 12;

    private final DataSource dataSource;
    private final SqlGuard sqlGuard;

    public QueryService(DataSource dataSource, SqlGuard sqlGuard) {
        this.dataSource = dataSource;
        this.sqlGuard = sqlGuard;
    }

    public SqlRunResult run(SqlRunRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        String sql = sqlGuard.validateReadOnly(request.sql());
        int maxRows = request.safeMaxRows();

        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try {
                QueryRows queryRows = executeRows(connection, sql, maxRows);
                List<String> plan = request.shouldExplain() ? explain(connection, sql) : List.of();
                Double planningTime = extractMetric(plan, PLANNING_TIME);
                Double executionTime = extractMetric(plan, EXECUTION_TIME);
                connection.rollback();

                return new SqlRunResult(
                        "POSTGRES",
                        queryRows.elapsedMs,
                        planningTime,
                        executionTime,
                        queryRows.rows.size(),
                        queryRows.truncated,
                        queryRows.columns,
                        queryRows.rows,
                        plan
                );
            } catch (RuntimeException | SQLException ex) {
                rollbackQuietly(connection);
                throw ex;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Database query failed: " + ex.getMessage(), ex);
        }
    }

    private QueryRows executeRows(Connection connection, String sql, int maxRows) throws SQLException {
        long started = System.nanoTime();
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.setMaxRows(maxRows + 1);
            try (ResultSet rs = statement.executeQuery(sql)) {
                ResultSetMetaData metadata = rs.getMetaData();
                List<String> columns = new ArrayList<>();
                for (int i = 1; i <= metadata.getColumnCount(); i++) {
                    columns.add(metadata.getColumnLabel(i));
                }

                List<Map<String, Object>> rows = new ArrayList<>();
                boolean truncated = false;
                while (rs.next()) {
                    if (rows.size() >= maxRows) {
                        truncated = true;
                        break;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= metadata.getColumnCount(); i++) {
                        row.put(columns.get(i - 1), jsonSafe(rs.getObject(i)));
                    }
                    rows.add(row);
                }
                return new QueryRows(columns, rows, truncated, roundMs(System.nanoTime() - started));
            }
        }
    }

    private List<String> explain(Connection connection, String sql) throws SQLException {
        List<String> plan = new ArrayList<>();
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (ResultSet rs = statement.executeQuery("EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) " + sql)) {
                while (rs.next()) {
                    plan.add(rs.getString(1));
                }
            }
        }
        return plan;
    }

    private Double extractMetric(List<String> lines, Pattern pattern) {
        for (String line : lines) {
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                return Double.parseDouble(matcher.group(1));
            }
        }
        return null;
    }

    private Object jsonSafe(Object value) throws SQLException {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof BigDecimal || value instanceof UUID || value instanceof TemporalAccessor) {
            return value.toString();
        }
        if (value instanceof Date || value instanceof Time || value instanceof Timestamp) {
            return value.toString();
        }
        if (value instanceof java.sql.Array sqlArray) {
            Object array = sqlArray.getArray();
            int length = Array.getLength(array);
            List<Object> values = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                values.add(jsonSafeValue(Array.get(array, i)));
            }
            return values;
        }
        return value.toString();
    }

    private Object jsonSafeValue(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return value.toString();
    }

    private void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
    }

    private double roundMs(long nanos) {
        return Math.round((nanos / 1_000_000.0) * 1000.0) / 1000.0;
    }

    private record QueryRows(
            List<String> columns,
            List<Map<String, Object>> rows,
            boolean truncated,
            double elapsedMs
    ) {
    }
}
