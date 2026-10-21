# WON-pick 전체(Market·Chat·Trade·Payment·Web)를 이 PC 한 대에서 띄운다. 서버마다 PowerShell 창이 하나씩 열린다.
#
#   scripts\run-all.cmd                   이 PC 브라우저에서만 시연 (http://localhost:5173), DB는 모두 이 PC의 MySQL
#   scripts\run-all.cmd -Share            강의장 다른 PC도 http://<내 IP>:5173 으로 접속
#   scripts\run-all.cmd -SharedMarketDb   Market만 팀 공유 DB(172.21.33.36 wonpick_market)에 붙는다
#   scripts\run-all.cmd -Pull             시작 전에 모든 저장소 git pull
#
# 저장소 5개가 같은 폴더(trade-service의 부모 폴더) 안에 있어야 한다. 없으면 clone 한다.
# DB·테이블은 각 서버가 스스로 만든다 (createDatabaseIfNotExist + schema.sql).
param(
    [switch]$Share,
    [switch]$Pull,
    [switch]$SharedMarketDb,
    [string]$DbUser = 'root',
    [string]$DbPassword = '1234',
    [string]$SharedMarketHost = '172.21.33.36',
    [string]$SharedMarketUser = 'wonpick_team',
    [string]$SharedMarketPassword = '1234'
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

function Test-Port([string]$hostName, [int]$port) {
    $client = New-Object Net.Sockets.TcpClient
    try { return $client.ConnectAsync($hostName, $port).Wait(3000) } catch { return $false } finally { $client.Close() }
}

if (-not (Test-Port 'localhost' 3306)) {
    throw '이 PC의 MySQL(3306)에 연결할 수 없습니다. MySQL 서비스를 켜고 다시 실행하세요.'
}

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

$jdbcOpts = 'serverTimezone=Asia/Seoul&characterEncoding=UTF-8'
$localJdbc = "createDatabaseIfNotExist=true&$jdbcOpts"

# ── Market DB: 기본은 이 PC, -SharedMarketDb 면 팀 공유 DB ──
$marketEnv = @{
    MARKET_DB_URL = "jdbc:mysql://localhost:3306/market_db?$localJdbc"
    MARKET_DB_USERNAME = $DbUser; MARKET_DB_PASSWORD = $DbPassword
    SPRING_SQL_INIT_MODE = 'always'          # 로컬 DB는 Market의 schema.sql로 테이블을 만든다
    TRADE_BASE_URL = "http://${ip}:8083"; MARKET_CORS_ALLOWED_ORIGINS = $origins
}
if ($SharedMarketDb) {
    if (Test-Port $SharedMarketHost 3306) {
        $marketEnv.MARKET_DB_URL = "jdbc:mysql://${SharedMarketHost}:3306/wonpick_market?$jdbcOpts"
        $marketEnv.MARKET_DB_USERNAME = $SharedMarketUser
        $marketEnv.MARKET_DB_PASSWORD = $SharedMarketPassword
        $marketEnv.SPRING_SQL_INIT_MODE = 'never'   # 공유 DB 테이블은 Market 담당자가 관리한다
        Write-Host "Market DB: 공유 DB ${SharedMarketHost}:3306/wonpick_market" -ForegroundColor Green
    } else {
        Write-Host "공유 Market DB(${SharedMarketHost}:3306)에 연결할 수 없어 이 PC의 MySQL을 씁니다." -ForegroundColor Yellow
    }
} else {
    Write-Host 'Market DB: 이 PC의 MySQL market_db' -ForegroundColor Green
}

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

Start-Window 'Market' (Join-Path $root 'market-service') 8082 $marketEnv '.\gradlew.bat bootRun'

Start-Window 'Chat' (Join-Path $root 'chat-service') 8080 @{
    SPRING_PROFILES_ACTIVE = 'mysql'
    CHAT_DB_URL = "jdbc:mysql://localhost:3306/chat_db?$localJdbc"; CHAT_DB_USER = $DbUser; CHAT_DB_PASSWORD = $DbPassword
} '.\gradlew.bat bootRun'

Start-Window 'Payment' (Join-Path $root 'payment-service\payment') 8084 @{
    PAYMENT_DB_URL = "jdbc:mysql://localhost:3306/payment_db?$localJdbc"
    PAYMENT_DB_USERNAME = $DbUser; PAYMENT_DB_PASSWORD = $DbPassword
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
Write-Host '가상 잔액: 처음 결제에 참여하는 회원은 1,000,000원 계좌가 자동으로 열립니다 (Payment).'
Write-Host 'Swagger: Market :8082 / Chat :8080 / Trade :8083 / Payment :8084  (각 /swagger-ui.html)'
Write-Host '끄기: scripts\stop-all.cmd'
