$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path 'target/v3-acceptance' | Out-Null
$mvn = if (Test-Path '.\mvnw.cmd') { '.\mvnw.cmd' } else { 'mvn' }
& $mvn -gs .mvn/settings-global-public.xml -q test
if ($LASTEXITCODE -ne 0) { throw 'V3 default Maven tests failed' }
& $mvn -gs .mvn/settings-global-public.xml -q "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=XlsxXirrReconTest,MoneyWeightedReturnCalculatorTest,PersonalFundToolContractTest,PersonalIsolationControllerTest,FundAgentSafetyPolicyTest,PersonalizationEvalV3Test" test
if ($LASTEXITCODE -ne 0) { throw 'V3 XIRR/tool/IDOR suite failed' }
Push-Location fund-web
if (Test-Path '..\tools\node-v22.23.2-win-x64\node.exe') {
  $env:PATH = "$(Resolve-Path '..\tools\node-v22.23.2-win-x64');$env:PATH"
}
npm run lint
if ($LASTEXITCODE -ne 0) { Pop-Location; throw 'fund-web lint failed' }
npm run test:ci
if ($LASTEXITCODE -ne 0) { Pop-Location; throw 'fund-web typecheck failed' }
npm run build
if ($LASTEXITCODE -ne 0) { Pop-Location; throw 'fund-web build failed' }
Remove-Item Env:E2E_BASE_URL -ErrorAction SilentlyContinue
npm run test:e2e
if ($LASTEXITCODE -ne 0) { Pop-Location; throw 'fund-web e2e gate failed' }
Pop-Location
if (Select-String -Path 'fund-web/lib/session.ts' -Pattern 'localStorage.*(accessToken|token)') { throw 'access token must not be written to localStorage' }
$mysql = 'skipped'
$browser = 'skipped'
try {
  & "$PSScriptRoot\start-mysql-it.ps1"
  $env:MYSQL_PASSWORD = 'test'
  $env:MYSQL_TEST_URL = 'jdbc:mysql://127.0.0.1:3307/jijing_agent_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
  $env:RUN_MYSQL_INTEGRATION_TESTS = 'true'
  $env:FUND_JWT_SIGNING_KEY = 'test-only-jwt-signing-key-32b-min'
  & $mvn -gs .mvn/settings-global-public.xml -q -pl fund-bootstrap -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=MySqlMigrationIntegrationTest,V3UserPortfolioMysqlIT,FlywayTargetedMigrationIT" test
  if ($LASTEXITCODE -eq 0) { $mysql = 'passed' } else { $mysql = 'failed' }
} catch { $mysql = "unavailable: $($_.Exception.Message)" }
if ($env:SKIP_V3_BROWSER_E2E -ne 'true' -and $mysql -eq 'passed') {
  try {
    & "$PSScriptRoot\run-v3-browser-e2e.ps1"
    if ($LASTEXITCODE -eq 0) { $browser = 'passed' } else { $browser = 'failed' }
  } catch { $browser = "unavailable: $($_.Exception.Message)" }
} else {
  $browser = 'skipped'
}
@{
  version = 'v3'
  mavenTests = 'passed'
  webLint = 'passed'
  webTypecheck = 'passed'
  webBuild = 'passed'
  webE2eGate = 'passed'
  tokenStorage = 'memory-only'
  httpIdorTests = 'PersonalIsolationControllerTest'
  xirrCsvSample = 'classpath:/xirr-excel-sample.csv'
  xirrXlsxRecon = 'XlsxXirrReconTest'
  personalToolContract = 'PersonalFundToolContractTest'
  flywayV1ToV14AndV9ToV14 = $mysql
  mysqlIntegration = $mysql
  playwrightBrowser = $browser
  notes = 'HTTP IDOR 404, cancel-only pause/resume 410, XIRR CSV+XLSX recon, memory token e2e-gate. MySQL IT on 127.0.0.1:3307 including Flyway target 8/14/18/23.'
} | ConvertTo-Json | Set-Content -Encoding utf8 'target/v3-acceptance/summary.json'
Write-Host 'V3 acceptance summary written to target/v3-acceptance/summary.json'
if ($mysql -eq 'failed') { throw 'MySQL integration tests failed' }
if ($browser -eq 'failed') { throw 'Playwright browser e2e failed' }
