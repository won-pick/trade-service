# WON-pick 전체(Market·Chat·Trade·Payment·Web)를 이 PC 한 대에서 띄운다. 서버마다 PowerShell 창이 하나씩 열린다.
#
#   scripts\run-all.cmd                 이 PC 브라우저에서만 시연 (http://localhost:5173)
#   scripts\run-all.cmd -Share          강의장 다른 PC도 http://<내 IP>:5173 으로 접속
#   scripts\run-all.cmd -Pull           시작 전에 모든 저장소 git pull
#
# 저장소 5개가 같은 폴더(trade-service의 부모 폴더) 안에 있어야 한다. 없으면 clone 한다.
param(
    [switch]$Share,
    [switch]$Pull,
    [string]$DbUser = 'root',
    [string]$DbPassword = '1234'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$repos = 'market-service', 'chat-service', 'trade-service', 'payment-service', 'won-pick-web'

Write-Host "WON-pick 작업 폴더: $root" -ForegroundColor Cyan

foreach ($repo in $repos) {
    $dir = Join-Path $root $repo
    if (-not (Test-Path $dir)) {
        Write-Host "  $repo 없음 → clone"
        git clone "https://github.com/won-pick/$repo.git" $dir
    } elseif ($Pull) {
        Write-Host "  $repo git pull"
        git -C $dir pull --ff-only
    }
}

# ── MySQL: Payment 테이블·테스트 계좌 (다른 서버는 스스로 DB·테이블을 만든다) ──
$mysql = (Get-Command mysql -ErrorAction SilentlyContinue).Source
if (-not $mysql) {
    $mysql = Get-ChildItem 'C:\Program Files\MySQL' -Recurse -Filter mysql.exe -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $mysql) {
    throw 'mysql.exe 를 찾지 못했습니다. MySQL 8 이 설치되어 있고 실행 중인지 확인하세요.'
}
$sql = (Join-Path $PSScriptRoot 'payment-local.sql') -replace '\\', '/'
$env:MYSQL_PWD = $DbPassword
& $mysql "-u$DbUser" -e "source $sql"
if ($LASTEXITCODE -ne 0) { throw 'Payment DB 준비 실패. MySQL 이 켜져 있는지, 계정·비밀번호가 맞는지 확인하세요.' }
Remove-Item Env:MYSQL_PWD
Write-Host 'MySQL 준비 완료 (payment_db 계좌: 회원 1~200번, 잔액 1,000,000원)' -ForegroundColor Green

# ── 주소 ──
$ip = 'localhost'
if ($Share) {
    $ip = Get-NetIPAddress -AddressFamily IPv4 |
        Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' -and $_.PrefixOrigin -ne 'WellKnown' } |
        Sort-Object { $_.IPAddress -notlike '172.21.*' } |
        Select-Object -First 1 -ExpandProperty IPAddress
    Write-Host "공유 모드: 다른 PC는 http://${ip}:5173 으로 접속" -ForegroundColor Yellow
}
$origins = 'http://localhost:5173'
if ($Share) { $origins += ",http://${ip}:5173" }

$jdbcOpts = 'createDatabaseIfNotExist=true&serverTimezone=Asia/Seoul&characterEncoding=UTF-8'

# ── 창 하나에 서버 하나 ──
function Start-Window([string]$title, [string]$dir, [int]$port, [hashtable]$envs, [string]$command) {
    if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) {
        Write-Host "  [$title] $port 포트를 이미 누가 쓰고 있어 건너뜀 (IntelliJ 등에서 켜 둔 서버를 끄고 다시 실행하세요)" -ForegroundColor Yellow
        return
    }
    $lines = @("`$host.UI.RawUI.WindowTitle = 'WON-pick $title :$port'", "Set-Location '$dir'")
    foreach ($key in $envs.Keys) { $lines += "`$env:$key = '$($envs[$key])'" }
    $lines += $command
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($lines -join "`n"))
    Start-Process powershell -ArgumentList '-NoExit', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', $encoded
    Write-Host "  [$title] http://localhost:$port 시작"
}

Start-Window 'Market' (Join-Path $root 'market-service') 8082 @{
    DB_URL = "jdbc:mysql://localhost:3306/wonpick_market?$jdbcOpts"; DB_USERNAME = $DbUser; DB_PASSWORD = $DbPassword
    MARKET_DB_URL = "jdbc:mysql://localhost:3306/wonpick_market?$jdbcOpts"; MARKET_DB_USERNAME = $DbUser; MARKET_DB_PASSWORD = $DbPassword
    TRADE_BASE_URL = "http://${ip}:8083"; ALLOWED_ORIGINS = $origins
} '.\gradlew.bat bootRun'

Start-Window 'Chat' (Join-Path $root 'chat-service') 8080 @{
    SPRING_PROFILES_ACTIVE = 'mysql'
    CHAT_DB_URL = "jdbc:mysql://localhost:3306/chat_db?$jdbcOpts"; CHAT_DB_USER = $DbUser; CHAT_DB_PASSWORD = $DbPassword
} '.\gradlew.bat bootRun'

Start-Window 'Payment' (Join-Path $root 'payment-service\payment') 8084 @{
    SERVER_PORT = '8084'
    SPRING_DATASOURCE_URL = "jdbc:mysql://localhost:3306/payment_db?$jdbcOpts"
    SPRING_DATASOURCE_USERNAME = $DbUser; SPRING_DATASOURCE_PASSWORD = $DbPassword
} '.\gradlew.bat bootRun'

Start-Window 'Trade' (Join-Path $root 'trade-service') 8083 @{
    MARKET_URL = 'http://localhost:8082'; CHAT_URL = 'http://localhost:8080'; PAYMENT_URL = 'http://localhost:8084'
    DB_USERNAME = $DbUser; DB_PASSWORD = $DbPassword; ALLOWED_ORIGINS = $origins
} '.\gradlew.bat bootRun'

$viteArgs = if ($Share) { 'npm run dev -- --host' } else { 'npm run dev' }
Start-Window 'Web' (Join-Path $root 'won-pick-web') 5173 @{
    VITE_MARKET_URL = "http://${ip}:8082"; VITE_TRADE_URL = "http://${ip}:8083"; CHAT_SERVER_URL = 'http://localhost:8080'
} "if (-not (Test-Path node_modules)) { npm install }; $viteArgs"

if ($Share) {
    $isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)
    $rule = Get-NetFirewallRule -DisplayName 'WON-pick' -ErrorAction SilentlyContinue
    if (-not $rule -and $isAdmin) {
        New-NetFirewallRule -DisplayName 'WON-pick' -Direction Inbound -Protocol TCP -LocalPort 5173, 8082, 8083 -Action Allow | Out-Null
        Write-Host '방화벽: 5173·8082·8083 인바운드 허용 규칙을 만들었습니다.' -ForegroundColor Green
    } elseif (-not $rule) {
        Write-Host '방화벽 규칙이 없습니다. 관리자 PowerShell 에서 한 번만 실행하세요:' -ForegroundColor Yellow
        Write-Host "  New-NetFirewallRule -DisplayName 'WON-pick' -Direction Inbound -Protocol TCP -LocalPort 5173,8082,8083 -Action Allow"
    }
}

Write-Host ''
Write-Host '서버가 모두 뜨는 데 1~2분 걸립니다. 그다음 http://localhost:5173 을 여세요.' -ForegroundColor Cyan
Write-Host 'Swagger: Market :8082 / Chat :8080 / Trade :8083 / Payment :8084  (각 /swagger-ui.html)'
Write-Host '끄기: scripts\stop-all.cmd'
