#Requires -RunAsAdministrator
# 감시 프로그램 제거: 자동 실행을 지우고, 막아 둔 장치를 다시 쓸 수 있게 되돌립니다.
# 제거했다는 기록을 서버에 남깁니다. 이 PC의 기록 사본(C:\ProgramData\UsbControl\logs)은 남겨 둡니다.

. (Join-Path $PSScriptRoot 'UsbControl.Common.ps1')

$TaskName = 'UsbControl'

if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
    Stop-ScheduledTask -TaskName $TaskName
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
}

Remove-InstallBlock
Remove-Tray
Set-WriteProtect $false   # 개인정보처리 PC의 읽기 전용 해제

foreach ($d in Get-AllControlledDevices -IncludePhones) {
    if ($d.Absent) {
        # 빠져 있는 장치는 '사용 안 함'을 풀 수 없어서 등록 정보를 지웁니다. 다시 꽂으면 새로 잡힙니다.
        & pnputil /remove-device $d.InstanceId | Out-Null
    } elseif ($d.Disabled) {
        Enable-PnpDevice -InstanceId $d.InstanceId -Confirm:$false -ErrorAction SilentlyContinue
    } elseif ($d.ErrorCode -ne 0) {
        # 설치 단계 차단에 막혀 있던 장치는 다시 설치
        & pnputil /remove-device $d.InstanceId | Out-Null
    }
}
& pnputil /scan-devices | Out-Null

$config = Read-Config
if ($config) {
    Add-UsbEvent '프로그램 제거' ([pscustomobject]@{ Kind = ''; Name = "감시 프로그램 $AgentVersion"; InstanceId = '' })
    try { Send-QueuedEvents $config } catch { Write-Host '서버에 제거 기록을 보내지 못했습니다.' -ForegroundColor Yellow }
}

Remove-Item -Path (Join-Path $InstallDir 'UsbControl.ps1'), (Join-Path $InstallDir 'UsbControl.Common.ps1'),
                  $ConfigPath, $PolicyCachePath, $QueuePath, $UsbStatePath, $PiStatePath, $PiResultPath,
                  (Join-Path $InstallDir 'UsbControlTray.cs') -ErrorAction SilentlyContinue

Write-Host ''
Write-Host '제거를 마쳤습니다. 이제 이 PC에서는 모든 USB를 쓸 수 있습니다.' -ForegroundColor Green
