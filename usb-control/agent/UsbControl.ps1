# USB 매체제어 감시 프로그램.
# install.ps1 이 시스템 계정 예약 작업으로 등록해서, 컴퓨터가 켜질 때마다 화면 없이 자동으로 실행됩니다.
#  - 2초마다: 꽂힌 USB를 확인해서 허용 목록에 없으면 '사용 안 함'으로 바꾸고 기록
#  - 30초마다: 서버에 기록을 보내고 최신 허용 목록과 설정을 받아 옴
# 서버에 연결이 안 되면 마지막으로 받은 정책으로 계속 막고, 기록은 모아 두었다가 나중에 보냅니다.

$CheckSeconds = 2
$SyncSeconds  = 30

. (Join-Path $PSScriptRoot 'UsbControl.Common.ps1')

$config = Read-Config
if (-not $config) {
    Write-ErrorLog '설정 파일이 없습니다. install.ps1 로 다시 설치하세요.'
    exit 1
}

$policy          = Read-CachedPolicy
$known           = @{}   # 직전 확인 때 꽂혀 있던 장치
$errorReported   = @{}   # 같은 오류를 계속 기록하지 않기 위한 표시
$reinstallTried  = @{}   # 설치 단계에서 막혔던 장치를 다시 설치해 본 기록
$lastSync        = [datetime]::MinValue
$lastSyncFailed  = $false

Write-ErrorLog "감시 시작 (버전 $AgentVersion, 정책 $($policy.version))"

function Update-Policy($NewPolicy) {
    Set-InstallBlock $NewPolicy.installBlock $NewPolicy.allow
    Save-CachedPolicy $NewPolicy
    $script:policy = $NewPolicy
    $script:reinstallTried = @{}
}

function Invoke-Sync {
    try {
        Send-QueuedEvents $config
        $newPolicy = Invoke-Checkin $config $policy.version
        if ($newPolicy.version -ne $policy.version) {
            Update-Policy $newPolicy
            Write-ErrorLog "새 정책 적용: $($newPolicy.version) (허용 $($newPolicy.allow.Count)개)"
        }
        if ($script:lastSyncFailed) { Write-ErrorLog '서버 연결 복구' }
        $script:lastSyncFailed = $false
    } catch {
        if (-not $script:lastSyncFailed) { Write-ErrorLog "서버 연결 실패 (복구될 때까지 저장된 정책으로 차단): $($_.Exception.Message)" }
        $script:lastSyncFailed = $true
    }
}

function Invoke-DeviceCheck {
    $present = @{}
    foreach ($d in Get-ControlledDevices -IncludePhones:$policy.blockPhones) {
        $id = $d.InstanceId
        $present[$id] = $true
        $isNew   = -not $known.ContainsKey($id)
        $allowed = Test-Allowed $id $policy.allow
        try {
            if ($allowed) {
                if ($d.Disabled) {
                    Enable-PnpDevice -InstanceId $id -Confirm:$false -ErrorAction Stop
                    Add-UsbEvent '허용(차단 풀림)' $d
                } elseif ($d.ErrorCode -ne 0 -and -not $reinstallTried.ContainsKey($id)) {
                    # 설치 단계 차단에 막혀 있던 장치가 새로 허용됨 → 다시 설치
                    $reinstallTried[$id] = $true
                    & pnputil /remove-device $id | Out-Null
                    & pnputil /scan-devices | Out-Null
                    Add-UsbEvent '허용(차단 풀림)' $d
                } elseif ($isNew) {
                    Add-UsbEvent '허용' $d
                }
            } else {
                if ($d.ErrorCode -eq 0) {
                    # 지금 쓸 수 있는 상태 → 끕니다
                    Disable-PnpDevice -InstanceId $id -Confirm:$false -ErrorAction Stop
                    Add-UsbEvent '차단' $d
                    if ($policy.notifyUser) { Send-BlockedMessage $d }
                } elseif ($isNew) {
                    if ($d.Disabled) {
                        Add-UsbEvent '차단(이미 차단된 장치)' $d
                    } else {
                        # 설치 단계 차단이 이미 막은 장치
                        Add-UsbEvent '차단' $d
                        if ($policy.notifyUser) { Send-BlockedMessage $d }
                    }
                }
            }
            $errorReported.Remove($id)
        } catch {
            if (-not $errorReported.ContainsKey($id)) {
                Write-ErrorLog "$id 처리 실패: $($_.Exception.Message)"
                $errorReported[$id] = $true
            }
        }
    }
    $script:known = $present
}

function Send-BlockedMessage($Device) {
    Send-UserMessage "허용되지 않은 $($Device.Kind)를 차단했습니다.`n$($Device.Name)`n`n사용하려면 관리자에게 등록을 요청하세요."
}

# 시작하자마자 저장된 정책을 다시 걸어 둡니다. (누가 레지스트리를 지웠어도 복구)
try { Set-InstallBlock $policy.installBlock $policy.allow } catch { Write-ErrorLog "설치 단계 차단 적용 실패: $($_.Exception.Message)" }

while ($true) {
    try {
        if (((Get-Date) - $lastSync).TotalSeconds -ge $SyncSeconds) {
            $lastSync = Get-Date
            Invoke-Sync
        }
        Invoke-DeviceCheck
    } catch {
        Write-ErrorLog "확인 중 오류: $($_.Exception.Message)"
    }
    Start-Sleep -Seconds $CheckSeconds
}
