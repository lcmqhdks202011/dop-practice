#Requires -RunAsAdministrator
# 지금 꽂혀 있는 USB 저장장치/휴대폰을 보여주고, 번호를 골라 허용 목록에 넣습니다.

. (Join-Path $PSScriptRoot 'UsbControl.Common.ps1')

$allowList = @(Read-AllowList)
$devices   = @(Get-ControlledDevices -IncludePhones)

if ($devices.Count -eq 0) {
    Write-Host '꽂혀 있는 USB 저장장치나 휴대폰이 없습니다.'
    return
}

Write-Host ''
for ($i = 0; $i -lt $devices.Count; $i++) {
    $d = $devices[$i]
    $state, $color = if (Test-Allowed $d.InstanceId $allowList) { '허용', 'Green' }
                     elseif ($d.ErrorCode -ne 0)               { '차단됨', 'Red' }
                     else                                      { '미등록', 'Yellow' }
    Write-Host ("[{0}] " -f ($i + 1)) -NoNewline
    Write-Host ("{0,-6}" -f $state) -ForegroundColor $color -NoNewline
    Write-Host (" {0} - {1}" -f $d.Kind, $d.Name)
    Write-Host ("      {0}" -f $d.InstanceId) -ForegroundColor DarkGray
}
Write-Host ''

$answer = Read-Host '허용할 번호를 입력하세요 (여러 개는 쉼표로 구분, 그냥 엔터는 취소)'
$added = 0
foreach ($n in ($answer -split ',')) {
    $n = $n.Trim()
    if (-not $n) { continue }
    $index = 0
    if (-not [int]::TryParse($n, [ref]$index) -or $index -lt 1 -or $index -gt $devices.Count) {
        Write-Host "'$n' 은(는) 없는 번호입니다." -ForegroundColor Red
        continue
    }
    $d = $devices[$index - 1]
    if (Test-Allowed $d.InstanceId $allowList) {
        Write-Host "[$index] 은(는) 이미 허용되어 있습니다."
        continue
    }
    Add-ToAllowList $d
    $added++
    Write-Host "[$index] $($d.Name) 을(를) 허용 목록에 넣었습니다." -ForegroundColor Green
}

if ($added -gt 0) {
    Write-Host ''
    Write-Host '프로그램 방식을 쓰고 있다면 몇 초 안에 사용할 수 있게 됩니다.'
    Write-Host '정책 방식을 쓰고 있다면 메뉴에서 [정책 방식 적용]을 한 번 더 실행하세요.'
}
