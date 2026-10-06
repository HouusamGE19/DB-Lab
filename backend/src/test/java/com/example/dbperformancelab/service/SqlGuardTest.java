package com.example.dbperformancelab.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SqlGuardTest {
    private final SqlGuard guard = new SqlGuard();

    @Test
    void allowsSelectAndWithQueries() {
        assertDoesNotThrow(() -> guard.validateReadOnly("SELECT * FROM orders LIMIT 5"));
        assertDoesNotThrow(() -> guard.validateReadOnly("WITH recent AS (SELECT * FROM orders) SELECT * FROM recent LIMIT 5"));
    }

    @Test
    void allowsOneTrailingSemicolon() {
        assertDoesNotThrow(() -> guard.validateReadOnly("SELECT 1;"));
    }

    @Test
    void blocksMutatingStatements() {
        assertThrows(IllegalArgumentException.class, () -> guard.validateReadOnly("DELETE FROM orders"));
        assertThrows(IllegalArgumentException.class, () -> guard.validateReadOnly("DROP TABLE orders"));
        assertThrows(IllegalArgumentException.class, () -> guard.validateReadOnly("WITH gone AS (DELETE FROM orders RETURNING *) SELECT * FROM gone"));
    }

    @Test
    void blocksMultipleStatementsAndDangerousFunctions() {
        assertThrows(IllegalArgumentException.class, () -> guard.validateReadOnly("SELECT 1; SELECT 2"));
        assertThrows(IllegalArgumentException.class, () -> guard.validateReadOnly("SELECT pg_sleep(10)"));
    }
}
