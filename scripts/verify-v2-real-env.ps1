param([switch]$StartLocalDependencies)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path 'target/v2-acceptance' | Out-Null
$mvn = if (Test-Path '.\mvnw.cmd') { '.\mvnw.cmd' } else { 'mvn' }
$preflight = @{}
foreach ($name in @('MYSQL_TEST_URL','MYSQL_PASSWORD','FUND_JWT_SIGNING_KEY','AI_CHAT_API_KEY','ELASTICSEARCH_URI','RAG_EVALUATION_DATASET_DIR')) {
  $preflight[$name] = [bool]([Environment]::GetEnvironmentVariable($name))
}
if ($StartLocalDependencies) {
  if (Get-Command docker -ErrorAction SilentlyContinue) {
    try { docker compose --profile mysql-test up -d mysql-test | Out-Null } catch { Write-Host 'docker mysql-test not started' }
  }
  if (Test-Path '.\scripts\start-mysql-it.ps1') { try { & '.\scripts\start-mysql-it.ps1' } catch { Write-Host $_ } }
}
$xml = @{
  realModel = 'not-run'
  embedding = 'not-run'
  elasticsearch = 'not-run'
  mysql = 'not-run'
}
if ($env:RUN_REAL_MODEL_SMOKE_TEST -eq 'true') {
  & $mvn -gs .mvn/settings-global-public.xml -pl fund-bootstrap "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=RealModelAgentSmokeTest" test
  $xml.realModel = if ($LASTEXITCODE -eq 0) { 'passed' } else { 'failed' }
}
if ($env:RUN_REAL_EMBEDDING_TEST -eq 'true') {
  & $mvn -gs .mvn/settings-global-public.xml -pl fund-bootstrap "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=RealEmbeddingSmokeTest" test
  $xml.embedding = if ($LASTEXITCODE -eq 0) { 'passed' } else { 'failed' }
}
if ($env:RUN_ELASTICSEARCH_INTEGRATION_TESTS -eq 'true') {
  & $mvn -gs .mvn/settings-global-public.xml -pl fund-infrastructure "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=RealElasticsearchIntegrationTest" test
  $xml.elasticsearch = if ($LASTEXITCODE -eq 0) { 'passed' } else { 'failed' }
}
if ($env:RUN_MYSQL_INTEGRATION_TESTS -eq 'true') {
  & $mvn -gs .mvn/settings-global-public.xml -pl fund-bootstrap -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=MySqlMigrationIntegrationTest,V3UserPortfolioMysqlIT" test
  $xml.mysql = if ($LASTEXITCODE -eq 0) { 'passed' } else { 'failed' }
}
$executed = @($xml.Values | Where-Object { $_ -in @('passed','failed') }).Count
$skipped = @($xml.Values | Where-Object { $_ -eq 'not-run' }).Count
@{
  version = 'v2-real-env'
  preflight = $preflight
  suites = $xml
  executedSuites = $executed
  skippedSuites = $skipped
  gate = if ($executed -ge 1 -and $skipped -eq 0 -and ($xml.Values | Where-Object { $_ -eq 'failed' }).Count -eq 0) { 'passed' } else { 'incomplete' }
  notes = 'V2 real-env requires Chat/Embedding/ES/MySQL/Provider suites with skipped=0. This script records executed vs skipped; incomplete is not a pass.'
} | ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 'target/v2-acceptance/summary.json'
Write-Host 'V2 real-env summary written to target/v2-acceptance/summary.json'
if ((Get-Content 'target/v2-acceptance/summary.json' -Raw) -match '"gate": "incomplete"') {
  Write-Host 'V2 real-env gate incomplete (required suites not all executed).'
}
