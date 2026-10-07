#Requires -RunAsAdministrator
# USB 매체제어 메뉴. usb-control.cmd 를 더블클릭하면 관리자 권한으로 이 화면이 뜹니다.

. (Join-Path $PSScriptRoot 'UsbControl.Common.ps1')

function Invoke-Step([string]$File) {
    try { & (Join-Path $PSScriptRoot $File) }
    catch { Write-Host "오류: $($_.Exception.Message)" -ForegroundColor Red }
}

while ($true) {
    $installed = [bool](Get-ScheduledTask -TaskName 'UsbControl' -ErrorAction SilentlyContinue)
    $policyOn  = [bool](Get-ItemProperty -Path 'HKLM:\SOFTWARE\Policies\Microsoft\Windows\DeviceInstall\Restrictions' -Name 'AllowDenyLayered' -ErrorAction SilentlyContinue)

    Write-Host ''
    Write-Host '========== USB 매체제어 ==========' -ForegroundColor Cyan
    Write-Host ("  프로그램 방식: {0}   정책 방식: {1}" -f $(if ($installed) { '켜짐' } else { '꺼짐' }), $(if ($policyOn) { '켜짐' } else { '꺼짐' }))
    Write-Host ''
    Write-Host '  1. 지금 꽂힌 USB 보기 / 허용 등록'
    Write-Host '  2. 허용 목록 파일 열기 (메모장)'
    Write-Host '  3. 사용 기록 보기'
    Write-Host ''
    Write-Host '  4. 프로그램 방식 설치  (감시 + 기록 + 알림창, 휴대폰도 차단)'
    Write-Host '  5. 프로그램 방식 제거'
    Write-Host ''
    Write-Host '  6. 정책 방식 적용  (프로그램 없이 윈도우 설정만, USB 저장장치만)'
    Write-Host '  7. 정책 방식 해제'
    Write-Host ''
    Write-Host '  0. 끝내기'
    $choice = Read-Host '번호'

    switch ($choice) {
        '1' { Invoke-Step 'list-usb.ps1' }
        '2' {
            if (-not (Test-Path $AllowListPath)) {
                New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
                Copy-Item -Path (Join-Path $PSScriptRoot 'allowlist.txt') -Destination $AllowListPath
            }
            Start-Process notepad.exe -ArgumentList "`"$AllowListPath`"" -Wait
            if ($policyOn) { Write-Host '정책 방식을 쓰고 있다면 6번을 다시 실행해야 바뀐 목록이 반영됩니다.' -ForegroundColor Yellow }
        }
        '3' {
            if (Test-Path $LogPath) {
                Import-Csv -Path $LogPath -Encoding UTF8 | Out-GridView -Title 'USB 사용 기록'
            } else {
                Write-Host '아직 기록이 없습니다.'
            }
        }
        '4' { Invoke-Step 'install.ps1' }
        '5' { Invoke-Step 'uninstall.ps1' }
        '6' { Invoke-Step 'policy-apply.ps1' }
        '7' { Invoke-Step 'policy-remove.ps1' }
        '0' { return }
    }
}
