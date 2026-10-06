# V3 frontend notes

V3 keeps the v2 backend contract and replaces the Angular presentation layer with an animated performance-lab UI.

The live architecture map reacts to the real `SqlRunResult.source` value:

- `POSTGRES` lights the Spring Boot → PostgreSQL path.
- `REDIS_HIT` lights the cache path and explains that PostgreSQL was skipped for the result payload.
- `REDIS_MISS_POSTGRES` lights Redis first, then PostgreSQL.

The plan visualizer reads the real `EXPLAIN ANALYZE` text already returned by the backend and detects common nodes including sequential scans, index scans, bitmap scans, nested loops, hash/merge joins, sorts and aggregates. It does not invent planner results.

Animations are implemented with CSS and SVG, so v3 adds no frontend runtime dependency beyond the Angular packages already used by v2.
