$ErrorActionPreference='Stop'
New-Item -ItemType Directory -Force -Path 'target/v3-acceptance' | Out-Null
& "$PSScriptRoot\start-mysql-it.ps1"
$env:MYSQL_PASSWORD='test'
$env:MYSQL_USERNAME='root'
$env:MYSQL_TEST_URL='jdbc:mysql://127.0.0.1:3307/jijing_agent_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
$env:FUND_JWT_SIGNING_KEY='test-only-jwt-signing-key-32b-min'
$env:SPRING_PROFILES_ACTIVE='test'
if (Test-Path '.\tools\node-v22.23.2-win-x64\node.exe') { $env:PATH = "$(Resolve-Path '.\tools\node-v22.23.2-win-x64');$env:PATH" }
Push-Location fund-web
npm install --no-fund --no-audit
if ($LASTEXITCODE -ne 0) { Pop-Location; throw 'npm install failed' }
Pop-Location
$mvn = if (Test-Path '.\mvnw.cmd') { (Resolve-Path '.\mvnw.cmd').Path } else { 'mvn' }
& $mvn -gs .mvn/settings-global-public.xml -pl fund-bootstrap -am -DskipTests package
if ($LASTEXITCODE -ne 0) { throw 'package for spring-boot e2e failed' }
$jar = Get-ChildItem 'fund-bootstrap/target/fund-bootstrap-*.jar' | Where-Object { $_.Name -notmatch 'sources|original' } | Select-Object -First 1
if (-not $jar) { throw 'repackaged spring-boot jar not found' }
$bootOut = Join-Path (Resolve-Path 'target/v3-acceptance') 'boot.out.log'
$bootErr = Join-Path (Resolve-Path 'target/v3-acceptance') 'boot.err.log'
$webOut = Join-Path (Resolve-Path 'target/v3-acceptance') 'web.out.log'
$webErr = Join-Path (Resolve-Path 'target/v3-acceptance') 'web.err.log'
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
if (-not (Test-Path $java)) { $java = 'java' }
$boot = Start-Process -FilePath $java -ArgumentList @('-jar',$jar.FullName,'--spring.profiles.active=test','--server.port=18080') -PassThru -WindowStyle Hidden -RedirectStandardOutput $bootOut -RedirectStandardError $bootErr
$ready = $false
foreach ($i in 1..90) {
  Start-Sleep -Seconds 2
  try { Invoke-WebRequest -UseBasicParsing http://127.0.0.1:18080/actuator/health | Out-Null; $ready = $true; break } catch {}
  if ($boot.HasExited) { throw "backend exited $($boot.ExitCode). See $bootOut / $bootErr" }
}
if (-not $ready) { Stop-Process -Id $boot.Id -Force -ErrorAction SilentlyContinue; throw "backend not ready on 18080. See $bootOut / $bootErr" }
$env:API_PROXY = 'http://127.0.0.1:18080'
$npm = (Get-Command npm.cmd).Source
$web = Start-Process -FilePath $npm -WorkingDirectory (Join-Path (Resolve-Path '.').Path 'fund-web') -ArgumentList @('run','dev','--','--port','3100','--host','0.0.0.0') -PassThru -WindowStyle Hidden -RedirectStandardOutput $webOut -RedirectStandardError $webErr
$webReady = $false
foreach ($i in 1..60) {
  Start-Sleep -Seconds 2
  try {
    $page = Invoke-WebRequest -UseBasicParsing http://localhost:3100/register
    if ($page.StatusCode -eq 200) { $webReady = $true; break }
  } catch {}
  if ($web.HasExited) { break }
}
try {
  if (-not $webReady) { throw "web not ready on 3100. See $webOut / $webErr" }
  $env:E2E_BASE_URL = 'http://localhost:3100'
  Push-Location fund-web
  node ./e2e/v3-browser.mjs
  if ($LASTEXITCODE -ne 0) { throw 'playwright e2e failed' }
  Pop-Location
} finally {
  if ($web -and -not $web.HasExited) { Stop-Process -Id $web.Id -Force -ErrorAction SilentlyContinue }
  if ($boot -and -not $boot.HasExited) { Stop-Process -Id $boot.Id -Force -ErrorAction SilentlyContinue }
  Get-CimInstance Win32_Process -Filter "name='node.exe'" -ErrorAction SilentlyContinue | Where-Object { $_.CommandLine -match 'vinext|playwright' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}
