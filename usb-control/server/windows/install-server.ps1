#Requires -RunAsAdministrator
# 서버 PC에 관리 서버 설치: 파일 복사 → 폴더 잠금 → 컴퓨터 켤 때 자동 실행 → 방화벽 포트 열기 → 시작
# 이 폴더에 usb-control-server.jar 가 같이 있어야 합니다. 자바 21 이상이 설치되어 있어야 합니다.

param(
    [string]$InstallDir = 'C:\UsbControlServer',
    [int]$Port = 8080
)

$ErrorActionPreference = 'Stop'
$TaskName = 'UsbControlServer'
$RuleName = 'USB 매체제어 관리 서버'
$jar = Join-Path $PSScriptRoot 'usb-control-server.jar'

if (-not (Test-Path $jar)) {
    Write-Host "usb-control-server.jar 가 이 폴더에 없습니다: $PSScriptRoot" -ForegroundColor Red
    exit 1
}

# 자바 찾기
$java = $null
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { $java = Join-Path $env:JAVA_HOME 'bin\java.exe' }
if (-not $java) { $java = (Get-Command java.exe -ErrorAction SilentlyContinue).Source }
if (-not $java) {
    Write-Host '자바를 찾을 수 없습니다. 자바 21 이상(예: Eclipse Temurin 21)을 설치한 뒤 다시 실행하세요.' -ForegroundColor Red
    exit 1
}
# 자바는 버전을 오류 출력으로 내보내므로 잠깐 오류 멈춤을 끕니다.
$ErrorActionPreference = 'Continue'
$versionText = (& $java -version 2>&1 | ForEach-Object { "$_" }) -join ' '
$ErrorActionPreference = 'Stop'
if ($versionText -match 'version "(\d+)') {
    if ([int]$Matches[1] -lt 21) {
        Write-Host "자바 $($Matches[1]) 이 설치되어 있습니다. 21 이상이 필요합니다." -ForegroundColor Red
        exit 1
    }
}
Write-Host "자바: $java"

if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
    Stop-ScheduledTask -TaskName $TaskName
    Start-Sleep -Seconds 3
}

New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
Copy-Item -Path $jar -Destination $InstallDir -Force
# 데이터베이스와 백업이 들어가는 폴더이므로 시스템과 관리자만 접근
& icacls $InstallDir /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' | Out-Null

$action    = New-ScheduledTaskAction -Execute $java -Argument "-jar usb-control-server.jar --server.port=$Port" -WorkingDirectory $InstallDir
$trigger   = New-ScheduledTaskTrigger -AtStartup
$principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
$settings  = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) `
               -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) `
               -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Force | Out-Null

# 사내망(개인/도메인 네트워크)에서만 접속 허용
Get-NetFirewallRule -DisplayName $RuleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule
New-NetFirewallRule -DisplayName $RuleName -Direction Inbound -Protocol TCP -LocalPort $Port -Action Allow -Profile Domain, Private | Out-Null

Start-ScheduledTask -TaskName $TaskName
Write-Host '서버를 시작하는 중...'
$url = "http://localhost:$Port/login"
$ok = $false
for ($i = 0; $i -lt 60 -and -not $ok; $i++) {
    Start-Sleep -Seconds 2
    try { Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 3 | Out-Null; $ok = $true } catch { }
}
if (-not $ok) {
    Write-Host "서버가 2분 안에 뜨지 않았습니다. $InstallDir\logs\server.log 를 확인하세요." -ForegroundColor Red
    exit 1
}

$ips = @(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
         Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } |
         ForEach-Object { "$($_.IPAddress):$Port" })

Write-Host ''
Write-Host '설치를 마쳤습니다.' -ForegroundColor Green
Write-Host "  관리 화면: http://localhost:$Port (다른 PC에서는 아래 주소)"
$ips | ForEach-Object { Write-Host "             http://$_" }
Write-Host "  데이터 폴더: $InstallDir\data, 백업 폴더: $InstallDir\backup"
Write-Host ''
Write-Host '네트워크가 "공용"으로 되어 있으면 다른 PC에서 접속이 안 됩니다. 윈도우 설정 > 네트워크에서 "개인"으로 바꾸세요.'
Start-Process "http://localhost:$Port"
