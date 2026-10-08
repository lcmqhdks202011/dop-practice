#Requires -RunAsAdministrator
# PC에 감시 프로그램 설치: 서버 연결 확인 → 파일 복사 → 폴더 잠금 → 컴퓨터 켤 때 자동 실행 등록 → 바로 시작
#   예) .\install.ps1 -Server 192.168.0.10:8080 -Key 관리화면_설정에_있는_키
#   값을 안 주면 이미 설치된 설정을 그대로 쓰고, 그것도 없으면 물어봅니다. (MSI 설치 파일도 이 스크립트를 부릅니다)

param(
    [string]$Server,
    [string]$Key
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'UsbControl.Common.ps1')

$TaskName = 'UsbControl'

# 새 버전으로 올릴 때는 기존 서버 주소와 키를 그대로 씁니다.
$existing = Read-Config
if (-not $Server -and $existing) { $Server = [string]$existing.serverUrl }
if (-not $Key -and $existing)    { $Key    = [string]$existing.agentKey }
if (-not $Server) { $Server = Read-Host '관리 서버 주소 (예: 192.168.0.10:8080)' }
if (-not $Key)    { $Key    = Read-Host '접속 키 (관리 화면 > 설정에 있음)' }
$Server = $Server.Trim()
$Key    = $Key.Trim()
if ($Server -notmatch '^https?://') { $Server = "http://$Server" }

Write-Host '서버 연결 확인 중...'
$config = [pscustomobject]@{ serverUrl = $Server; agentKey = $Key }
try {
    $policy = Invoke-Checkin $config ''
} catch {
    $status = $null
    try { $status = [int]$_.Exception.Response.StatusCode } catch { }
    if ($status -eq 401) {
        Write-Host '접속 키가 틀립니다. 관리 화면 > 설정의 키를 다시 확인하세요.' -ForegroundColor Red
    } else {
        Write-Host "서버에 연결할 수 없습니다: $($_.Exception.Message)" -ForegroundColor Red
        Write-Host '서버가 켜져 있는지, 주소와 포트가 맞는지, 서버 PC 방화벽에서 포트를 열었는지 확인하세요.'
    }
    exit 1
}
Write-Host "연결 성공 (허용 매체 $($policy.allow.Count)개)" -ForegroundColor Green

if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
    Stop-ScheduledTask -TaskName $TaskName
}

New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
# 관리자와 시스템만 고칠 수 있고, 일반 사용자는 읽기만 가능
#   S-1-5-18 = 시스템 계정, S-1-5-32-544 = 관리자 그룹, S-1-5-32-545 = 사용자 그룹
& icacls $InstallDir /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' '*S-1-5-32-545:(OI)(CI)RX' | Out-Null

Copy-Item -Path (Join-Path $PSScriptRoot 'UsbControl.ps1'), (Join-Path $PSScriptRoot 'UsbControl.Common.ps1') -Destination $InstallDir -Force
Save-Config $Server $Key

# 설치 기록을 남기고 바로 보냅니다.
Add-UsbEvent '프로그램 설치' ([pscustomobject]@{ Kind = ''; Name = "감시 프로그램 $AgentVersion"; InstanceId = '' })
try { Send-QueuedEvents $config } catch { }

$powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$script     = Join-Path $InstallDir 'UsbControl.ps1'
$action     = New-ScheduledTaskAction -Execute $powershell -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$script`""
$trigger    = New-ScheduledTaskTrigger -AtStartup
$principal  = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
$settings   = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) `
                -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) `
                -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable

Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Force | Out-Null
Start-ScheduledTask -TaskName $TaskName

Write-Host ''
Write-Host '설치를 마쳤습니다. 지금부터 허용되지 않은 USB는 차단됩니다.' -ForegroundColor Green
Write-Host '관리 화면 > PC 적용 현황에 이 PC가 1분 안에 나타납니다.'
