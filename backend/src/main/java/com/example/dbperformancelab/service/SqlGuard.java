package com.example.dbperformancelab.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class SqlGuard {
    private static final Pattern LEADING_COMMENTS = Pattern.compile("(?s)^(?:\\s|--[^\\n]*(?:\\n|$)|/\\*.*?\\*/)*");
    private static final List<String> FORBIDDEN_TOKENS = List.of(
            "INSERT", "UPDATE", "DELETE", "MERGE", "CREATE", "ALTER", "DROP", "TRUNCATE",
            "GRANT", "REVOKE", "VACUUM", "ANALYZE", "COPY", "CALL", "DO", "SET", "RESET",
            "PG_SLEEP", "PG_READ_FILE", "PG_READ_BINARY_FILE", "PG_LS_DIR", "PG_TERMINATE_BACKEND",
            "PG_CANCEL_BACKEND", "NEXTVAL", "SETVAL", "DBLINK"
    );

    public String validateReadOnly(String rawSql) {
        if (rawSql == null || rawSql.isBlank()) {
            throw new IllegalArgumentException("SQL cannot be empty.");
        }

        String sql = rawSql.trim();
        if (sql.length() > 20_000) {
            throw new IllegalArgumentException("SQL is too long for this lab.");
        }

        if (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        if (sql.contains(";")) {
            throw new IllegalArgumentException("Only one SQL statement can be executed at a time.");
        }

        String withoutLeadingComments = LEADING_COMMENTS.matcher(sql).replaceFirst("").trim();
        String upper = withoutLeadingComments.toUpperCase(Locale.ROOT);
        if (!(upper.startsWith("SELECT ") || upper.startsWith("SELECT\n") || upper.startsWith("WITH ") || upper.startsWith("WITH\n"))) {
            throw new IllegalArgumentException("Only SELECT and read-only WITH queries are allowed in the SQL console.");
        }

        String searchable = " " + upper.replaceAll("[^A-Z0-9_]+", " ") + " ";
        for (String token : FORBIDDEN_TOKENS) {
            if (searchable.contains(" " + token + " ")) {
                throw new IllegalArgumentException("Forbidden token in read-only SQL console: " + token);
            }
        }

        return sql;
    }
}
