import { CommonModule } from '@angular/common';
import { ChangeDetectorRef, Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';

interface ColumnMeta {
  name: string;
  dataType: string;
}

interface TableMeta {
  name: string;
  estimatedRows: number;
  columns: ColumnMeta[];
}

interface TableStats {
  table: string;
  liveRows: number;
  sequentialScans: number;
  indexScans: number;
  inserted: number;
  updated: number;
  deleted: number;
}

interface DatabaseMetrics {
  databaseBytes: number;
  activeConnections: number;
  blocksRead: number;
  blocksHit: number;
  cacheHitRatio: number;
  commits: number;
  rollbacks: number;
  tables: TableStats[];
}

interface SqlRunResult {
  source: string;
  elapsedMs: number;
  planningTimeMs: number | null;
  executionTimeMs: number | null;
  returnedRows: number;
  truncated: boolean;
  columns: string[];
  rows: Array<Record<string, unknown>>;
  explainPlan: string[];
}

interface IndexInfo {
  name: string;
  table: string;
  definition: string;
  sizeBytes: number;
  scans: number;
  managed: boolean;
}

interface SeedResult {
  counts: Record<string, number>;
  elapsedMs: number;
}

interface QueryPreset {
  name: string;
  description: string;
  suggestedIndex: string;
  sql: string;
}

interface RunHistoryItem {
  source: string;
  elapsedMs: number;
  executionTimeMs: number | null;
  query: string;
}

interface PlanSignal {
  label: string;
  detail: string;
  tone: 'good' | 'warn' | 'info' | 'violet';
}

type FlowMode = 'IDLE' | 'RUNNING' | 'POSTGRES' | 'REDIS_HIT' | 'REDIS_MISS_POSTGRES' | 'ERROR';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './app.component.html'
})
export class AppComponent {
  private readonly http = inject(HttpClient);
  private readonly cdr = inject(ChangeDetectorRef);

  readonly indexMethods = ['BTREE', 'HASH', 'GIN', 'GIST', 'BRIN'];
  readonly scanCells = Array.from({ length: 72 });
  readonly presets: QueryPreset[] = [
    {
      name: 'Latest orders for one user',
      description: 'Join users + orders and sort one customer’s newest orders.',
      suggestedIndex: 'BTREE orders(user_id, created_at DESC)',
      sql: `SELECT o.id, u.email, o.status, o.total, o.created_at
FROM orders o
JOIN users u ON u.id = o.user_id
WHERE o.user_id = 42
ORDER BY o.created_at DESC
LIMIT 50`
    },
    {
      name: 'Top-selling products',
      description: 'Aggregate a large join across order_items, products and categories.',
      suggestedIndex: 'Try BTREE order_items(product_id), then compare plans.',
      sql: `SELECT p.id, p.name, c.name AS category,
       SUM(oi.quantity) AS units,
       SUM(oi.quantity * oi.unit_price) AS revenue
FROM order_items oi
JOIN products p ON p.id = oi.product_id
JOIN categories c ON c.id = p.category_id
GROUP BY p.id, p.name, c.name
ORDER BY revenue DESC
LIMIT 25`
    },
    {
      name: 'Customer lifetime value',
      description: 'Group orders by user and filter on aggregate spend.',
      suggestedIndex: 'BTREE orders(user_id) helps the join/group workload.',
      sql: `SELECT u.id, u.email, COUNT(o.id) AS order_count,
       SUM(o.total) AS lifetime_value
FROM users u
JOIN orders o ON o.user_id = u.id
GROUP BY u.id, u.email
HAVING SUM(o.total) > 5000
ORDER BY lifetime_value DESC
LIMIT 50`
    },
    {
      name: 'Products by tag (GIN)',
      description: 'Array containment is a natural GIN experiment.',
      suggestedIndex: 'GIN products(tags)',
      sql: `SELECT id, name, price, tags
FROM products
WHERE tags @> ARRAY['sale']
ORDER BY id
LIMIT 100`
    },
    {
      name: 'Recent order window (BRIN)',
      description: 'Orders are physically generated in timestamp order, making BRIN interesting.',
      suggestedIndex: 'BRIN orders(created_at)',
      sql: `SELECT status, COUNT(*) AS orders, SUM(total) AS revenue
FROM orders
WHERE created_at >= now() - interval '7 days'
GROUP BY status
ORDER BY revenue DESC`
    },
    {
      name: 'JSONB brand lookup (GIN)',
      description: 'Containment query over product JSONB attributes.',
      suggestedIndex: 'GIN products(attributes)',
      sql: `SELECT id, name, price, attributes
FROM products
WHERE attributes @> '{"brand":"Brand 7"}'::jsonb
LIMIT 100`
    }
  ];

  tables: TableMeta[] = [];
  counts: Record<string, number> = {};
  indexes: IndexInfo[] = [];
  metrics: DatabaseMetrics | null = null;

  seedUsers = 20000;
  seedProducts = 50000;
  seedOrders = 250000;

  selectedPreset = 0;
  sql = this.presets[0].sql;
  maxRows = 100;
  explainAnalyze = true;
  cacheTtlSeconds = 60;
  queryResult: SqlRunResult | null = null;
  history: RunHistoryItem[] = [];

  indexName = '';
  indexMethod = 'BTREE';
  indexTable = 'orders';
  indexColumn1 = 'user_id';
  indexDirection1 = 'ASC';
  indexColumn2 = 'created_at';
  indexDirection2 = 'DESC';
  indexIncludes = '';
  indexConcurrent = false;

  busyAction: string | null = null;
  message = 'Seed the dataset, run a query, then change indexes and compare the plan.';

  labEntered = false;
  enteringLab = false;
  activeSection = 'console';
  flowMode: FlowMode = 'IDLE';
  flowRequestedCache = false;

  constructor() {
    void this.initialize();
  }

  get selectedTable(): TableMeta | undefined {
    return this.tables.find(table => table.name === this.indexTable);
  }

  get totalRows(): number {
    return Object.values(this.counts).reduce((sum, value) => sum + value, 0);
  }

  get databaseReady(): boolean {
    return this.totalRows > 0;
  }

  get explainText(): string {
    return this.queryResult?.explainPlan.join('\n') ?? '';
  }

  get usesIndex(): boolean {
    return /(Index Scan|Index Only Scan|Bitmap Index Scan)/i.test(this.explainText);
  }

  get usesSequentialScan(): boolean {
    return /Seq Scan/i.test(this.explainText);
  }

  get flowLabel(): string {
    if (this.flowMode === 'RUNNING') return 'REQUEST IN FLIGHT';
    if (this.flowMode === 'REDIS_HIT') return 'CACHE HIT';
    if (this.flowMode === 'REDIS_MISS_POSTGRES') return 'CACHE MISS → POSTGRES';
    if (this.flowMode === 'POSTGRES') return 'POSTGRES EXECUTION';
    if (this.flowMode === 'ERROR') return 'REQUEST FAILED';
    return 'READY';
  }

  get flowDescription(): string {
    if (this.flowMode === 'RUNNING') return this.flowRequestedCache ? 'Checking Redis before database execution…' : 'Sending SQL to PostgreSQL…';
    if (this.flowMode === 'REDIS_HIT') return 'Result payload returned from Redis. PostgreSQL was skipped.';
    if (this.flowMode === 'REDIS_MISS_POSTGRES') return 'Redis missed, PostgreSQL executed the query, then the result was cached.';
    if (this.flowMode === 'POSTGRES') return this.usesIndex ? 'PostgreSQL executed the query using an index path.' : 'PostgreSQL executed the query directly.';
    if (this.flowMode === 'ERROR') return 'The backend rejected or failed the request. Check the status message.';
    return 'Run a query to watch the request move through the stack.';
  }

  get planSignals(): PlanSignal[] {
    const plan = this.explainText;
    if (!plan) return [];

    const signals: PlanSignal[] = [];
    const add = (test: RegExp, label: string, detail: string, tone: PlanSignal['tone']) => {
      if (test.test(plan)) signals.push({ label, detail, tone });
    };

    add(/Index Only Scan/i, 'Index Only Scan', 'Rows can be served from the index without a heap lookup.', 'good');
    add(/Index Scan/i, 'Index Scan', 'PostgreSQL is navigating an index instead of scanning every row.', 'good');
    add(/Bitmap (Index|Heap) Scan/i, 'Bitmap Scan', 'PostgreSQL builds a bitmap of matching pages before reading rows.', 'info');
    add(/Seq Scan/i, 'Sequential Scan', 'PostgreSQL is scanning table pages row-by-row.', 'warn');
    add(/Hash Join/i, 'Hash Join', 'One side of the join is hashed for fast matching.', 'violet');
    add(/Nested Loop/i, 'Nested Loop', 'PostgreSQL repeats the inner lookup for rows from the outer input.', 'info');
    add(/Merge Join/i, 'Merge Join', 'Sorted inputs are merged together.', 'violet');
    add(/Sort/i, 'Sort', 'The plan includes an explicit sorting step.', 'warn');
    add(/Aggregate|GroupAggregate|HashAggregate/i, 'Aggregate', 'Rows are being grouped or aggregated.', 'violet');

    return signals;
  }

  async initialize(): Promise<void> {
    await this.refreshAll();
  }

  enterLab(): void {
    if (this.enteringLab) return;
    this.enteringLab = true;
    this.cdr.detectChanges();
    window.setTimeout(() => {
      this.labEntered = true;
      this.enteringLab = false;
      this.cdr.detectChanges();
    }, 850);
  }

  goTo(section: string): void {
    this.activeSection = section;
    this.cdr.detectChanges();

    window.requestAnimationFrame(() => {
      const target = document.getElementById(section);
      if (!target) return;

      const stickyHeaderOffset = 86;
      const top = target.getBoundingClientRect().top + window.scrollY - stickyHeaderOffset;
      window.scrollTo({ top: Math.max(0, top), behavior: 'smooth' });
    });
  }

  async refreshAll(): Promise<void> {
    try {
      const [tables, counts, indexes, metrics] = await Promise.all([
        firstValueFrom(this.http.get<TableMeta[]>('/api/lab/schema')),
        firstValueFrom(this.http.get<Record<string, number>>('/api/lab/counts')),
        firstValueFrom(this.http.get<IndexInfo[]>('/api/lab/indexes')),
        firstValueFrom(this.http.get<DatabaseMetrics>('/api/lab/metrics'))
      ]);
      this.tables = tables;
      this.counts = counts;
      this.indexes = indexes;
      this.metrics = metrics;
      this.ensureIndexColumnsAreValid();
    } catch (error) {
      this.message = `Backend not ready: ${this.errorMessage(error)}`;
    } finally {
      this.cdr.detectChanges();
    }
  }

  loadPreset(): void {
    this.sql = this.presets[this.selectedPreset].sql;
    this.queryResult = null;
    this.flowMode = 'IDLE';
    this.message = `Loaded: ${this.presets[this.selectedPreset].name}. Suggested experiment: ${this.presets[this.selectedPreset].suggestedIndex}`;
    this.cdr.detectChanges();
  }

  async seed(): Promise<void> {
    await this.runAction('seed', async () => {
      this.message = 'Seeding users, products, orders and related rows… Existing lab indexes will be reset.';
      this.cdr.detectChanges();
      const result = await firstValueFrom(this.http.post<SeedResult>('/api/lab/seed', {
        users: this.seedUsers,
        products: this.seedProducts,
        orders: this.seedOrders
      }));
      this.queryResult = null;
      this.history = [];
      this.flowMode = 'IDLE';
      this.message = `Seed complete in ${(result.elapsedMs / 1000).toFixed(2)} s. Created ${Object.values(result.counts).reduce((a, b) => a + b, 0).toLocaleString()} total rows.`;
      await this.refreshAll();
    });
  }

  async runSql(cached: boolean): Promise<void> {
    this.flowRequestedCache = cached;
    this.flowMode = 'RUNNING';
    this.cdr.detectChanges();

    await this.runAction(cached ? 'cache-query' : 'query', async () => {
      const endpoint = cached ? '/api/lab/sql/cached' : '/api/lab/sql/run';
      const result = await firstValueFrom(this.http.post<SqlRunResult>(endpoint, {
        sql: this.sql,
        maxRows: this.maxRows,
        explainAnalyze: this.explainAnalyze,
        cacheTtlSeconds: this.cacheTtlSeconds
      }));
      this.queryResult = result;
      this.flowMode = result.source === 'REDIS_HIT'
        ? 'REDIS_HIT'
        : result.source === 'REDIS_MISS_POSTGRES'
          ? 'REDIS_MISS_POSTGRES'
          : 'POSTGRES';
      this.history.unshift({
        source: result.source,
        elapsedMs: result.elapsedMs,
        executionTimeMs: result.executionTimeMs,
        query: this.sql.replace(/\s+/g, ' ').trim().slice(0, 90)
      });
      this.history = this.history.slice(0, 12);
      this.message = result.source === 'REDIS_HIT'
        ? 'Redis hit: PostgreSQL was skipped for the result payload.'
        : result.source === 'REDIS_MISS_POSTGRES'
          ? 'Redis miss: PostgreSQL ran the query, then the result was cached.'
          : 'PostgreSQL query complete. Compare the result and EXPLAIN ANALYZE plan.';
      await this.refreshMetricsAndIndexes();
    });
  }

  async clearCache(): Promise<void> {
    await this.runAction('clear-cache', async () => {
      const result = await firstValueFrom(this.http.delete<{ deleted: number }>('/api/lab/sql/cache'));
      this.message = `Cleared ${result.deleted} cached SQL result(s).`;
      this.flowMode = 'IDLE';
    });
  }

  onIndexTableChange(): void {
    const columns = this.selectedTable?.columns ?? [];
    this.indexColumn1 = columns[0]?.name ?? '';
    this.indexColumn2 = '';
    this.indexIncludes = '';
  }

  async createIndex(): Promise<void> {
    await this.runAction('create-index', async () => {
      const columns = [
        { name: this.indexColumn1, direction: this.indexDirection1 },
        ...(this.indexColumn2 ? [{ name: this.indexColumn2, direction: this.indexDirection2 }] : [])
      ];
      const includeColumns = this.indexIncludes
        .split(',')
        .map(value => value.trim())
        .filter(value => value.length > 0);

      const created = await firstValueFrom(this.http.post<IndexInfo>('/api/lab/indexes', {
        name: this.indexName,
        table: this.indexTable,
        method: this.indexMethod,
        columns,
        includeColumns,
        concurrently: this.indexConcurrent
      }));
      this.indexName = '';
      this.message = `Created ${created.name}. Run the same SQL again and inspect how the plan changed.`;
      await this.refreshMetricsAndIndexes();
    });
  }

  async dropIndex(index: IndexInfo): Promise<void> {
    if (!index.managed) return;

    await this.runAction('drop-index', async () => {
      await firstValueFrom(this.http.delete(`/api/lab/indexes/${encodeURIComponent(index.name)}`));
      this.message = `Dropped ${index.name}.`;
      await this.refreshMetricsAndIndexes();
    });
  }

  formatValue(value: unknown): string {
    if (value === null || value === undefined) return 'NULL';
    if (typeof value === 'object') return JSON.stringify(value);
    return String(value);
  }

  formatBytes(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
    return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
  }

  private async refreshMetricsAndIndexes(): Promise<void> {
    const [indexes, metrics] = await Promise.all([
      firstValueFrom(this.http.get<IndexInfo[]>('/api/lab/indexes')),
      firstValueFrom(this.http.get<DatabaseMetrics>('/api/lab/metrics'))
    ]);
    this.indexes = indexes;
    this.metrics = metrics;
  }

  private ensureIndexColumnsAreValid(): void {
    if (!this.tables.length) return;
    if (!this.tables.some(table => table.name === this.indexTable)) {
      this.indexTable = this.tables[0].name;
    }
    const columns = this.selectedTable?.columns ?? [];
    if (!columns.some(column => column.name === this.indexColumn1)) {
      this.indexColumn1 = columns[0]?.name ?? '';
    }
    if (this.indexColumn2 && !columns.some(column => column.name === this.indexColumn2)) {
      this.indexColumn2 = '';
    }
  }

  private async runAction(name: string, action: () => Promise<void>): Promise<void> {
    this.busyAction = name;
    this.cdr.detectChanges();
    try {
      await action();
    } catch (error) {
      console.error(error);
      if (name === 'query' || name === 'cache-query') this.flowMode = 'ERROR';
      this.message = this.errorMessage(error);
    } finally {
      this.busyAction = null;
      this.cdr.detectChanges();
    }
  }

  private errorMessage(error: unknown): string {
    if (error instanceof HttpErrorResponse) {
      const body = error.error as { message?: string } | null;
      return body?.message ?? error.message;
    }
    return error instanceof Error ? error.message : 'Unknown error.';
  }
}
