package com.example.dbperformancelab.service;

import com.example.dbperformancelab.dto.SeedRequest;
import com.example.dbperformancelab.dto.SeedResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class SeedService {
    private final JdbcTemplate jdbc;
    private final IndexService indexService;

    public SeedService(JdbcTemplate jdbc, IndexService indexService) {
        this.jdbc = jdbc;
        this.indexService = indexService;
    }

    public SeedResult seed(SeedRequest request) {
        int users = clamp(request.users(), 1_000, 100_000, 20_000);
        int products = clamp(request.products(), 1_000, 500_000, 50_000);
        int orders = clamp(request.orders(), 1_000, 2_000_000, 250_000);

        long start = System.nanoTime();
        indexService.dropAllManagedIndexes();
        jdbc.execute("DROP INDEX IF EXISTS idx_orders_user_created_at");

        jdbc.execute("TRUNCATE TABLE reviews, order_items, orders, inventory, products, categories, users RESTART IDENTITY CASCADE");

        jdbc.update("""
                INSERT INTO categories(name)
                SELECT 'Category ' || g
                FROM generate_series(1, 40) AS g
                """);

        jdbc.update("""
                INSERT INTO users(email, full_name, country, created_at)
                SELECT
                    'user' || g || '@example.com',
                    'User ' || g,
                    (ARRAY['MA','FR','ES','US','DE','GB','CA','NL'])[((g - 1) % 8) + 1],
                    now() - ((g % 1095) * interval '1 day')
                FROM generate_series(1, ?) AS g
                """, users);

        jdbc.update("""
                INSERT INTO products(category_id, sku, name, description, price, active, tags, attributes, created_at)
                SELECT
                    ((g - 1) % 40) + 1,
                    'SKU-' || lpad(g::text, 8, '0'),
                    'Product ' || g,
                    'Synthetic benchmark product ' || g || ' for indexing and join experiments',
                    round((5 + ((g * 37) % 100000) / 100.0)::numeric, 2),
                    (g % 20) <> 0,
                    ARRAY[
                        (ARRAY['new','sale','popular','eco','premium'])[((g - 1) % 5) + 1],
                        (ARRAY['home','tech','sport','office','travel'])[((g * 3 - 1) % 5) + 1]
                    ],
                    jsonb_build_object(
                        'brand', 'Brand ' || ((g % 50) + 1),
                        'color', (ARRAY['black','white','blue','green','red'])[((g - 1) % 5) + 1]
                    ),
                    now() - ((g % 730) * interval '1 day')
                FROM generate_series(1, ?) AS g
                """, products);

        jdbc.execute("""
                INSERT INTO inventory(product_id, stock_quantity, warehouse, updated_at)
                SELECT
                    id,
                    ((id * 17) % 500)::int,
                    (ARRAY['north','south','east','west'])[(((id - 1) % 4) + 1)::int],
                    now() - ((id % 72) * interval '1 hour')
                FROM products
                """);

        jdbc.update("""
                INSERT INTO orders(user_id, total, status, created_at)
                SELECT
                    ((g::bigint * 7919) % ?) + 1,
                    round((20 + ((g * 53) % 200000) / 100.0)::numeric, 2),
                    (ARRAY['NEW','PAID','SHIPPED','CANCELLED'])[((g - 1) % 4) + 1],
                    now() - ((? - g) * interval '20 seconds')
                FROM generate_series(1, ?) AS g
                """, users, orders, orders);

        jdbc.update("""
                INSERT INTO order_items(order_id, product_id, quantity, unit_price)
                SELECT
                    o.id,
                    p.id,
                    1 + ((o.id + x.n) % 4)::int,
                    p.price
                FROM orders o
                CROSS JOIN LATERAL generate_series(1, 1 + (o.id % 3)::int) AS x(n)
                JOIN products p ON p.id = (((o.id * 31 + x.n * 17) % ?) + 1)
                """, products);

        int reviews = Math.min(products, Math.max(1_000, products / 2));
        jdbc.update("""
                INSERT INTO reviews(user_id, product_id, rating, comment, created_at)
                SELECT
                    ((g * 3571) % ?) + 1,
                    ((g * 7919) % ?) + 1,
                    1 + (g % 5)::int,
                    'Synthetic review ' || g,
                    now() - ((g % 365) * interval '1 day')
                FROM generate_series(1, ?) AS g
                """, users, products, reviews);

        jdbc.execute("ANALYZE users");
        jdbc.execute("ANALYZE categories");
        jdbc.execute("ANALYZE products");
        jdbc.execute("ANALYZE inventory");
        jdbc.execute("ANALYZE orders");
        jdbc.execute("ANALYZE order_items");
        jdbc.execute("ANALYZE reviews");

        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : MetadataService.LAB_TABLES) {
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
            counts.put(table, count == null ? 0L : count);
        }

        return new SeedResult(counts, roundMs(System.nanoTime() - start));
    }

    private int clamp(int value, int min, int max, int fallback) {
        if (value <= 0) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }

    private double roundMs(long nanos) {
        return Math.round((nanos / 1_000_000.0) * 1000.0) / 1000.0;
    }
}
