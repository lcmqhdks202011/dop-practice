#Requires -RunAsAdministrator
# 프로그램 방식 설치: 파일 복사 → 폴더 잠금 → 컴퓨터 켤 때 자동 실행 등록 → 지금 바로 시작

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'UsbControl.Common.ps1')

$TaskName = 'UsbControl'

if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
    Stop-ScheduledTask -TaskName $TaskName
}

New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
Copy-Item -Path (Join-Path $PSScriptRoot 'UsbControl.ps1'), (Join-Path $PSScriptRoot 'UsbControl.Common.ps1') -Destination $InstallDir -Force
if (-not (Test-Path $AllowListPath)) {
    Copy-Item -Path (Join-Path $PSScriptRoot 'allowlist.txt') -Destination $AllowListPath
}

# 관리자와 시스템만 고칠 수 있고, 일반 사용자는 읽기만 가능 (허용 목록을 마음대로 못 고치게)
#   S-1-5-18 = 시스템 계정, S-1-5-32-544 = 관리자 그룹, S-1-5-32-545 = 사용자 그룹
& icacls $InstallDir /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' '*S-1-5-32-545:(OI)(CI)RX' | Out-Null

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
Write-Host '설치를 마쳤습니다. 지금부터 허용 목록에 없는 USB는 차단됩니다.' -ForegroundColor Green
Write-Host "  허용 목록: $AllowListPath"
Write-Host "  기록 파일: $LogPath"
