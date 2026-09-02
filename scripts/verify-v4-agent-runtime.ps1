$ErrorActionPreference = 'Stop'
& "$PSScriptRoot\verify-v3-user-portfolio.ps1"
New-Item -ItemType Directory -Force -Path 'target/v4-acceptance' | Out-Null
$mvn = if (Test-Path '.\mvnw.cmd') { '.\mvnw.cmd' } else { 'mvn' }
& $mvn -gs .mvn/settings-global-public.xml -q "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=HybridRuntimeTest,PlanValidatorTest,ExecutionModeRouterTest,ApprovalServiceTest,PersonalIsolationControllerTest" test
if ($LASTEXITCODE -ne 0) { throw 'V4 runtime/validator/router suite failed' }
$mysql = 'skipped'
$realModel = if ($env:OPENAI_API_KEY -or $env:AI_CHAT_API_KEY) { 'not-executed-set-RUN_REAL_MODEL_SMOKE_TEST' } else { 'skipped-no-OPENAI_API_KEY' }
try {
  & "$PSScriptRoot\start-mysql-it.ps1"
  $env:MYSQL_PASSWORD = 'test'
  $env:MYSQL_TEST_URL = 'jdbc:mysql://127.0.0.1:3307/jijing_agent_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
  $env:RUN_MYSQL_INTEGRATION_TESTS = 'true'
  $env:FUND_JWT_SIGNING_KEY = 'test-only-jwt-signing-key-32b-min'
  & $mvn -gs .mvn/settings-global-public.xml -q -pl fund-bootstrap -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=MySqlMigrationIntegrationTest,FlywayTargetedMigrationIT" test
  if ($LASTEXITCODE -eq 0) { $mysql = 'passed' } else { $mysql = 'failed' }
} catch { $mysql = "unavailable: $($_.Exception.Message)" }
if ($env:RUN_REAL_MODEL_SMOKE_TEST -eq 'true' -and ($env:OPENAI_API_KEY -or $env:AI_CHAT_API_KEY)) {
  & $mvn -gs .mvn/settings-global-public.xml -pl fund-bootstrap "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=RealModelAgentSmokeTest" test
  $realModel = if ($LASTEXITCODE -eq 0) { 'passed' } else { 'failed' }
}
@{
  version = 'v4'
  mavenTests = 'passed'
  routerPlanValidatorDag = 'HybridRuntimeTest+PlanValidatorTest+ExecutionModeRouterTest'
  twoWorkerClaim = 'HybridRuntimeTest.twoWorkersNeverClaimTheSameTask'
  leaseRecoveryWithoutRepeat = 'HybridRuntimeTest.expiredLeaseIsRecoveredWithoutRepeatingSuccess'
  lastEventIdReplay = 'HybridRuntimeTest.planExecutesDagAndIsolatesOwners'
  approvalParameterHash = 'HybridRuntimeTest.exportWaitsForApprovalAndParameterHashMustMatch'
  cancelOnlyNoPause = 'pause/resume HTTP 410 CANCEL_ONLY'
  mysqlV1ToV18AndV14ToV18 = $mysql
  realModelLongRun = $realModel
  notes = 'Cancel-only (no pause/resume). Process death simulated by expired task lease recovery. Real-model cost/quality requires OPENAI_API_KEY.'
} | ConvertTo-Json | Set-Content -Encoding utf8 'target/v4-acceptance/summary.json'
Write-Host 'V4 acceptance summary written to target/v4-acceptance/summary.json'
if ($mysql -eq 'failed') { throw 'V4 MySQL integration tests failed' }
