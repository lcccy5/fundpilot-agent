$ErrorActionPreference = 'Stop'
$basedir = 'C:/Program Files/MySQL/MySQL Server 8.0'
$mysqld = Join-Path $basedir 'bin/mysqld.exe'
$mysql = Join-Path $basedir 'bin/mysql.exe'
$root = (Resolve-Path '.').Path
$datadir = Join-Path $root 'target/mysql-it-data'
$cnf = Join-Path $root 'target/mysql-it.cnf'
New-Item -ItemType Directory -Force -Path (Join-Path $root 'target') | Out-Null
if (-not (Test-Path $mysqld)) { throw "mysqld not found at $mysqld" }
@"
[mysqld]
basedir=$basedir
datadir=$($datadir -replace '\\','/')
port=3307
bind-address=127.0.0.1
mysqlx=0
lc-messages-dir=$basedir/share
pid-file=$($root -replace '\\','/')/target/mysql-it.pid
log-error=$($root -replace '\\','/')/target/mysql-it.err
"@ | Set-Content -Encoding ascii $cnf
if (-not (Test-Path (Join-Path $datadir 'mysql'))) {
  New-Item -ItemType Directory -Force -Path $datadir | Out-Null
  & $mysqld --defaults-file=$cnf --initialize-insecure
  if ($LASTEXITCODE -ne 0) { throw 'mysqld --initialize-insecure failed' }
}
$listening = @(Get-NetTCPConnection -LocalPort 3307 -State Listen -ErrorAction SilentlyContinue)
if ($listening.Count -eq 0) {
  $p = Start-Process -FilePath $mysqld -ArgumentList @("--defaults-file=$cnf") -WindowStyle Hidden -PassThru
  $ready = $false
  foreach ($i in 1..40) {
    Start-Sleep -Seconds 1
    cmd /c "`"$mysql`" --protocol=tcp --host=127.0.0.1 --port=3307 -uroot --connect-expired-password -e `"SELECT 1`" >NUL 2>&1"
    if ($LASTEXITCODE -eq 0) { $ready = $true; break }
    if ($p.HasExited) { throw "mysqld exited $($p.ExitCode). See target/mysql-it.err" }
  }
  if (-not $ready) { throw 'mysqld on 3307 did not become ready. See target/mysql-it.err' }
  $sql = "CREATE DATABASE IF NOT EXISTS jijing_agent_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; CREATE USER IF NOT EXISTS 'root'@'127.0.0.1' IDENTIFIED BY 'test'; ALTER USER 'root'@'localhost' IDENTIFIED BY 'test'; ALTER USER 'root'@'127.0.0.1' IDENTIFIED BY 'test'; GRANT ALL ON *.* TO 'root'@'localhost'; GRANT ALL ON *.* TO 'root'@'127.0.0.1'; FLUSH PRIVILEGES;"
  cmd /c "`"$mysql`" --protocol=tcp --host=127.0.0.1 --port=3307 -uroot -e `"$sql`" >NUL 2>&1"
  if ($LASTEXITCODE -ne 0) {
    cmd /c "`"$mysql`" --protocol=tcp --host=127.0.0.1 --port=3307 -uroot -ptest -e `"$sql`" >NUL 2>&1"
  }
}
cmd /c "`"$mysql`" --protocol=tcp --host=127.0.0.1 --port=3307 -uroot -ptest -e `"CREATE DATABASE IF NOT EXISTS jijing_agent_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;`" >NUL 2>&1"
Write-Host 'mysql-it ready on 127.0.0.1:3307 database jijing_agent_test password test'

