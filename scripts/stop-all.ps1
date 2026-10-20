# WON-pick 포트(5173·8080·8082·8083·8084)를 듣고 있는 프로세스를 모두 끈다. IntelliJ에서 켠 서버도 함께 꺼진다.
$ports = 5173, 8080, 8082, 8083, 8084
foreach ($port in $ports) {
    $owners = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique
    foreach ($processId in $owners) {
        $name = (Get-Process -Id $processId -ErrorAction SilentlyContinue).ProcessName
        Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
        Write-Host "  :$port $name (pid $processId) 종료"
    }
}
Write-Host '완료. 열려 있던 서버 창은 닫아도 됩니다.'
