#Requires -RunAsAdministrator
# 관리 서버 자동 실행과 방화벽 규칙을 지웁니다. 데이터와 백업 폴더는 남겨 둡니다.

$TaskName = 'UsbControlServer'
$RuleName = 'USB 매체제어 관리 서버'

if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
    Stop-ScheduledTask -TaskName $TaskName
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
}
Get-NetFirewallRule -DisplayName $RuleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule

Write-Host '관리 서버를 멈추고 자동 실행을 지웠습니다. 데이터(C:\UsbControlServer\data)는 남아 있습니다.' -ForegroundColor Green
