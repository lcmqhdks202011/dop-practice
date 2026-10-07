#Requires -RunAsAdministrator
# 프로그램 방식 제거: 자동 실행을 지우고, 이 프로그램이 막아 둔 장치를 다시 쓸 수 있게 되돌립니다.
# 허용 목록과 기록 파일은 남겨 둡니다. (C:\ProgramData\UsbControl)

. (Join-Path $PSScriptRoot 'UsbControl.Common.ps1')

$TaskName = 'UsbControl'

if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
    Stop-ScheduledTask -TaskName $TaskName
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
}
Remove-Item -Path (Join-Path $InstallDir 'UsbControl.ps1'), (Join-Path $InstallDir 'UsbControl.Common.ps1') -ErrorAction SilentlyContinue

foreach ($d in Get-AllControlledDevices -IncludePhones) {
    if ($d.Absent) {
        # 빠져 있는 장치는 '사용 안 함'을 풀 수 없어서 등록 정보를 지웁니다. 다시 꽂으면 새로 잡힙니다.
        & pnputil /remove-device $d.InstanceId | Out-Null
    } elseif ($d.Disabled) {
        Enable-PnpDevice -InstanceId $d.InstanceId -Confirm:$false -ErrorAction SilentlyContinue
    }
}

Write-Host ''
Write-Host '제거를 마쳤습니다. 이제 모든 USB를 쓸 수 있습니다.' -ForegroundColor Green
