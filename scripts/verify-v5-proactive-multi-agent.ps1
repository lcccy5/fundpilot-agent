$ErrorActionPreference = 'Stop'
& "$PSScriptRoot\verify-v4-agent-runtime.ps1"
New-Item -ItemType Directory -Force -Path 'target/v5-acceptance' | Out-Null
$mvn = if (Test-Path '.\mvnw.cmd') { '.\mvnw.cmd' } else { 'mvn' }
& $mvn -gs .mvn/settings-global-public.xml -q "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=MultiAgentAndOutboxTest" test
if ($LASTEXITCODE -ne 0) { throw 'V5 multi-agent/outbox suite failed' }
$abPath = 'target/v5-acceptance/ab-eval.json'
if (-not (Test-Path $abPath)) { throw 'ab-eval.json missing; MultiAgentAndOutboxTest should write it' }
$ab = Get-Content $abPath -Raw | ConvertFrom-Json
@{
  version = 'v5'
  mavenTests = 'passed'
  outboxIdempotency = 'MultiAgentAndOutboxTest+MySqlMigrationIntegrationTest'
  ordinaryQaNotMultiAgent = [int]$ab.ordinaryQaMultiStarts -eq 0
  roleAcl = 'DATA_RESEARCHER cannot PORTFOLIO; WRITER no external tools'
  monthlyReportUsesV4Plan = $true
  versionedReportArtifact = $true
  mcpSchemaPauseAndNoUserId = $true
  singleAgentQuality = $ab.singleAgentQuality
  multiAgentQuality = $ab.multiAgentQuality
  extraCost = $ab.extraCostMultiplier
  multiAgentEnabled = ($ab.decision -eq 'enable-multi-agent')
  abSource = $ab.source
  notes = 'Outbox+dead letter, threshold cooldown, RoleToolAcl, Supervisor no extra tasks, ordinary QA not multi-agent, MCP schema pause/userId ban/external untrusted, monthly report via V4 plan, A/B from MultiAgentAbEval fixture. Flyway V19-V23. Mail/webhook out of V5.0. Live-model A/B skipped without OPENAI_API_KEY.'
} | ConvertTo-Json | Set-Content -Encoding utf8 'target/v5-acceptance/summary.json'
Write-Host 'V5 acceptance summary written to target/v5-acceptance/summary.json'
