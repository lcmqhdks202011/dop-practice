# USB 매체제어 감시 프로그램.
# install.ps1 이 시스템 계정 예약 작업으로 등록해서, 컴퓨터가 켜질 때마다 화면 없이 자동으로 실행됩니다.
# 허용 목록에 없는 USB 저장장치/휴대폰이 꽂히면 '사용 안 함'으로 바꾸고 기록을 남깁니다.

$PollSeconds = 2       # 몇 초마다 확인할지
$BlockPhones = $true   # 휴대폰 파일 전송도 막을지
$NotifyUser  = $true   # 차단할 때 사용자 화면에 알림 창을 띄울지

. (Join-Path $PSScriptRoot 'UsbControl.Common.ps1')

$known         = @{}   # 직전 확인 때 꽂혀 있던 장치
$errorReported = @{}   # 같은 오류를 계속 기록하지 않기 위한 표시
$allowList     = @()
$allowListTime = $null

Write-ErrorLog '감시 시작'

while ($true) {
    try {
        # 허용 목록 파일이 바뀌었으면 다시 읽기
        $time = if (Test-Path $AllowListPath) { (Get-Item $AllowListPath).LastWriteTimeUtc } else { $null }
        if ($time -ne $allowListTime) {
            $allowList     = @(Read-AllowList)
            $allowListTime = $time
        }

        $present = @{}
        foreach ($d in Get-ControlledDevices -IncludePhones:$BlockPhones) {
            $present[$d.InstanceId] = $true
            $isNew   = -not $known.ContainsKey($d.InstanceId)
            $allowed = Test-Allowed $d.InstanceId $allowList
            try {
                if ($allowed -and $d.Disabled) {
                    Enable-PnpDevice -InstanceId $d.InstanceId -Confirm:$false -ErrorAction Stop
                    Write-UsbLog '허용(차단 풀림)' $d
                } elseif (-not $allowed -and -not $d.Disabled) {
                    Disable-PnpDevice -InstanceId $d.InstanceId -Confirm:$false -ErrorAction Stop
                    Write-UsbLog '차단' $d
                    if ($NotifyUser) {
                        Send-UserMessage "허용되지 않은 $($d.Kind)를 차단했습니다.`n$($d.Name)`n`n사용하려면 관리자에게 등록을 요청하세요."
                    }
                } elseif ($isNew) {
                    Write-UsbLog $(if ($allowed) { '허용' } else { '차단(이미 차단된 장치)' }) $d
                }
                $errorReported.Remove($d.InstanceId)
            } catch {
                if (-not $errorReported.ContainsKey($d.InstanceId)) {
                    Write-ErrorLog "$($d.InstanceId) 처리 실패: $($_.Exception.Message)"
                    $errorReported[$d.InstanceId] = $true
                }
            }
        }
        $known = $present
    } catch {
        Write-ErrorLog "확인 중 오류: $($_.Exception.Message)"
    }
    Start-Sleep -Seconds $PollSeconds
}
