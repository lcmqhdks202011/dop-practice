#Requires -RunAsAdministrator
# 정책 방식 해제: policy-apply.ps1 이 넣은 설정만 지웁니다.

. (Join-Path $PSScriptRoot 'UsbControl.Common.ps1')

$RestrictionsKey = 'HKLM:\SOFTWARE\Policies\Microsoft\Windows\DeviceInstall\Restrictions'

if (Test-Path $RestrictionsKey) {
    foreach ($name in 'AllowDenyLayered', 'DenyDeviceClasses', 'DenyDeviceClassesRetroactive', 'AllowInstanceIDs') {
        Remove-ItemProperty -Path $RestrictionsKey -Name $name -ErrorAction SilentlyContinue
    }
    foreach ($name in 'DenyDeviceClasses', 'AllowInstanceIDs') {
        Remove-Item -Path (Join-Path $RestrictionsKey $name) -Recurse -ErrorAction SilentlyContinue
    }
}

# 정책에 막혀 설치가 안 된 USB를 지워서 다시 잡히게 합니다.
foreach ($d in Get-AllControlledDevices) {
    if ($d.ErrorCode -ne 0 -and -not $d.Disabled) {
        & pnputil /remove-device $d.InstanceId | Out-Null
    }
}
& pnputil /scan-devices | Out-Null

Write-Host ''
Write-Host '정책을 해제했습니다. 막혀 있던 USB는 한 번 뺐다가 다시 꽂으면 쓸 수 있습니다.' -ForegroundColor Green
