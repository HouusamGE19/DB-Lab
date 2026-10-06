$ErrorActionPreference = "Stop"
$base = "http://localhost:8080/api/lab"

Write-Host "1. Checking schema..."
Invoke-RestMethod "$base/schema" | Out-Null

Write-Host "2. Seeding a small test dataset..."
$seed = @{ users = 2000; products = 5000; orders = 10000 } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$base/seed" -ContentType "application/json" -Body $seed | Format-List

Write-Host "3. Running SQL..."
$query = @{
  sql = "SELECT o.id, o.user_id, o.created_at FROM orders o WHERE o.user_id = 42 ORDER BY o.created_at DESC LIMIT 20"
  maxRows = 50
  explainAnalyze = $true
  cacheTtlSeconds = 60
} | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$base/sql/run" -ContentType "application/json" -Body $query | Select-Object source, elapsedMs, executionTimeMs, returnedRows | Format-List

Write-Host "4. Creating a B-tree index..."
$index = @{
  table = "orders"
  method = "BTREE"
  columns = @(
    @{ name = "user_id"; direction = "ASC" },
    @{ name = "created_at"; direction = "DESC" }
  )
  includeColumns = @()
  concurrently = $false
} | ConvertTo-Json -Depth 5
Invoke-RestMethod -Method Post -Uri "$base/indexes" -ContentType "application/json" -Body $index | Select-Object name, table, definition | Format-List

Write-Host "5. Running cached query twice..."
Invoke-RestMethod -Method Delete -Uri "$base/sql/cache" | Out-Null
$first = Invoke-RestMethod -Method Post -Uri "$base/sql/cached" -ContentType "application/json" -Body $query
$second = Invoke-RestMethod -Method Post -Uri "$base/sql/cached" -ContentType "application/json" -Body $query
Write-Host "First:  $($first.source) - $($first.elapsedMs) ms"
Write-Host "Second: $($second.source) - $($second.elapsedMs) ms"

if ($first.source -ne "REDIS_MISS_POSTGRES" -or $second.source -ne "REDIS_HIT") {
  throw "Redis cache smoke test failed."
}

Write-Host "Smoke test passed."
