#Requires -RunAsAdministrator
# 정책 방식: 프로그램을 띄워 두지 않고 윈도우 '장치 설치 제한' 정책만으로 막습니다.
#   - 새로 꽂는 디스크는 설치 금지
#   - 단, 허용 목록에 있는 장치는 예외로 설치 허용
# 그룹 정책 편집기(gpedit.msc)에서 같은 항목을 손으로 켜는 것과 똑같은 설정을 레지스트리에 씁니다.
# 허용 목록을 바꾼 뒤에는 이 스크립트를 다시 실행해야 반영됩니다.

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'UsbControl.Common.ps1')

$RestrictionsKey = 'HKLM:\SOFTWARE\Policies\Microsoft\Windows\DeviceInstall\Restrictions'
$DiskDriveClass  = '{4d36e967-e325-11ce-bfc1-08002be10318}'   # 장치 관리자의 '디스크 드라이브' 종류

# '허용이 금지보다 우선' 설정은 윈도우 10 2004(빌드 19041) 이후부터 됩니다.
$build = [int](Get-CimInstance -ClassName Win32_OperatingSystem).BuildNumber
if ($build -lt 19041) {
    Write-Host "이 윈도우(빌드 $build)는 너무 오래되어 정책 방식을 쓸 수 없습니다. 윈도우 10 2004 이상으로 업데이트하거나 프로그램 방식을 쓰세요." -ForegroundColor Red
    return
}

$allowList = @(Read-AllowList)
$exact     = @($allowList | Where-Object { -not $_.Contains('*') })
if ($exact.Count -lt $allowList.Count) {
    Write-Host '별표(*)가 들어간 줄은 정책 방식에서 쓸 수 없어 건너뜁니다.' -ForegroundColor Yellow
}

function Set-ListKey([string]$Name, [string[]]$Values) {
    $path = Join-Path $RestrictionsKey $Name
    if (Test-Path $path) { Remove-Item -Path $path -Recurse }
    New-Item -Path $path -Force | Out-Null
    for ($i = 0; $i -lt $Values.Count; $i++) {
        New-ItemProperty -Path $path -Name ([string]($i + 1)) -Value $Values[$i] -PropertyType String | Out-Null
    }
}

New-Item -Path $RestrictionsKey -Force | Out-Null
# 모든 장치 일치 조건에 대해 허용/금지를 계층 순서로 평가 (장치 ID 허용이 종류 금지보다 우선)
Set-ItemProperty -Path $RestrictionsKey -Name 'AllowDenyLayered'             -Value 1 -Type DWord
# 이 장치 종류와 일치하는 장치 설치 금지
Set-ItemProperty -Path $RestrictionsKey -Name 'DenyDeviceClasses'            -Value 1 -Type DWord
Set-ItemProperty -Path $RestrictionsKey -Name 'DenyDeviceClassesRetroactive' -Value 0 -Type DWord  # 1로 하면 내장 디스크까지 지워지므로 절대 켜지 않음
Set-ListKey 'DenyDeviceClasses' @($DiskDriveClass)
# 이 장치 ID와 일치하는 장치 설치 허용
Set-ItemProperty -Path $RestrictionsKey -Name 'AllowInstanceIDs'             -Value 1 -Type DWord
Set-ListKey 'AllowInstanceIDs' $exact

# 정책은 '새로 설치되는' 장치에만 걸리므로, 예전에 한 번이라도 꽂았던 USB 등록 정보를 지워서 다시 설치되게 만듭니다.
#   - 허용 안 된 장치: 지움 → 다시 잡힐 때 정책에 막힘
#   - 허용된 장치인데 지금 막혀 있음: 지움 → 다시 잡힐 때 정상 설치
$removed = 0
foreach ($d in Get-AllControlledDevices) {
    $allowed = Test-Allowed $d.InstanceId $exact
    if ((-not $allowed) -or ($d.ErrorCode -ne 0 -and -not $d.Disabled)) {
        & pnputil /remove-device $d.InstanceId | Out-Null
        $removed++
    }
}
& pnputil /scan-devices | Out-Null

Write-Host ''
Write-Host '정책을 적용했습니다.' -ForegroundColor Green
Write-Host "  허용된 장치: $($exact.Count)개 / 정리한 USB 등록 정보: $removed개"
Write-Host '  지금 꽂혀 있는 USB가 바로 바뀌지 않으면 한 번 뺐다가 다시 꽂으세요.'
