# USB 매체제어 감시 프로그램.
# install.ps1 이 시스템 계정 예약 작업으로 등록해서, 컴퓨터가 켜질 때마다 화면 없이 자동으로 실행됩니다.
#  - 2초마다: 꽂힌 USB를 확인해서 허용 목록에 없으면 '사용 안 함'으로 바꾸고 기록
#  - 2초마다: 지정 포트에 키보드·마우스가 그대로 있는지 확인해서, 빠지거나 바뀌거나 다른 포트에 꽂히면 기록
#  - 개인정보처리 PC: 허용 USB도 읽기 전용으로 열고('쓰기 허용' 매체만 쓰기), USB로 복사한 파일을 기록
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
$inputDevices    = @()   # 지금 꽂혀 있는 키보드·마우스 (서버에 알림)
$portState       = @{}   # 지정 포트별 상태: ok / missing / changed
$portMissing     = @{}   # 지정 포트가 연속으로 비어 있던 횟수
$extraInputs     = @{}   # 지정 포트가 아닌 곳에 꽂혀 있는 키보드·마우스
$inputErrorShown = $false
$writeProtected  = Test-WriteProtect   # 지금 저장장치 쓰기를 막아 두었는지
$watched         = @{}   # 파일 반출을 지켜보는 USB: 장치 ID → @{ Device; Roots; Tries }
$pendingFiles    = @{}   # 바뀐 파일 경로 → 마지막으로 바뀐 시각 (복사가 끝날 때까지 기다림)
$loggedFiles     = @{}   # 이미 기록한 파일 경로 → @{ Size; Time } (같은 복사를 두 번 기록하지 않음)
$missedReported  = @{}   # 드라이브 → '기록 누락'을 마지막으로 남긴 시각

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
        $newPolicy = Invoke-Checkin $config $policy.version $inputDevices
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
    $usable  = @()   # 지금 쓸 수 있게 열어 둔 허용 장치
    foreach ($d in Get-ControlledDevices -IncludePhones:$policy.blockPhones) {
        $id = $d.InstanceId
        $present[$id] = $true
        $isNew   = -not $known.ContainsKey($id)
        $allowed = Test-Allowed $id $policy.allow
        try {
            if ($allowed) {
                $usable += $d
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
                    if ($policy.privacyPc -and -not (Test-Allowed $id $policy.writable)) {
                        Add-UsbEvent '허용(읽기 전용)' $d
                    } else {
                        Add-UsbEvent '허용' $d
                    }
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

    try { Update-WriteProtect $usable } catch { Write-ErrorLog "읽기 전용 설정 실패: $($_.Exception.Message)" }
    try { Update-FileWatch $usable } catch { Write-ErrorLog "파일 반출 감시 설정 실패: $($_.Exception.Message)" }
}

# 개인정보처리 PC: 꽂혀 있는 허용 장치가 모두 '쓰기 허용' 매체일 때만 쓰기를 엽니다. 아무것도 없을 때는 막아 둡니다.
# (쓰기 허용 매체와 아닌 매체를 같이 꽂으면 둘 다 읽기 전용이 됩니다.)
function Update-WriteProtect($Usable) {
    $want = $false
    if ($policy.privacyPc) {
        $readOnly = @($Usable | Where-Object { -not (Test-Allowed $_.InstanceId $policy.writable) })
        $want = $Usable.Count -eq 0 -or $readOnly.Count -gt 0
    }
    if ($want -eq $script:writeProtected) { return }

    Set-WriteProtect $want
    $script:writeProtected = $want
    Write-ErrorLog $(if ($want) { '저장장치 읽기 전용 켬' } else { '저장장치 쓰기 허용 (쓰기 허용 매체만 꽂혀 있음)' })
    # 이미 꽂혀 있는 장치는 끄고 다시 켜서 바뀐 설정으로 다시 연결합니다.
    foreach ($d in $Usable) {
        Disable-PnpDevice -InstanceId $d.InstanceId -Confirm:$false -ErrorAction SilentlyContinue
        Enable-PnpDevice -InstanceId $d.InstanceId -Confirm:$false -ErrorAction SilentlyContinue
        Stop-FileWatch $d.InstanceId   # 드라이브가 다시 잡히면 새로 지켜봅니다
    }
}

# 개인정보처리 PC: 열어 둔 USB 저장장치의 드라이브를 지켜봅니다.
function Update-FileWatch($Usable) {
    $now = @{}
    if ($policy.privacyPc) {
        foreach ($d in $Usable | Where-Object { $_.Kind -eq 'USB저장장치' }) {
            $now[$d.InstanceId] = $true
            $w = $watched[$d.InstanceId]
            if (-not $w) {
                $w = @{ Device = $d; Roots = @(); Tries = 0 }
                $watched[$d.InstanceId] = $w
            }
            # 꽂은 직후에는 드라이브 문자가 아직 없을 수 있어 30초 동안 다시 찾아봅니다.
            if ($w.Roots.Count -gt 0 -or $w.Tries -ge 15) { continue }
            $w.Tries++
            $w.Roots = @(Get-DriveRoots $d.InstanceId)
            foreach ($root in $w.Roots) { [UsbControlFiles]::Watch($root) }
            if ($w.Tries -ge 15 -and $w.Roots.Count -eq 0) { Write-ErrorLog "$($d.InstanceId) 드라이브를 찾지 못해 파일 반출을 기록할 수 없음" }
        }
    }
    foreach ($id in @($watched.Keys)) {
        if (-not $now.ContainsKey($id)) { Stop-FileWatch $id }
    }
}

function Stop-FileWatch([string]$InstanceId) {
    $w = $watched[$InstanceId]
    if (-not $w) { return }
    foreach ($root in $w.Roots) { [UsbControlFiles]::Unwatch($root) }
    $watched.Remove($InstanceId)
}

# USB에 생기거나 바뀐 파일을 '파일 반출'로 기록합니다. 2초 넘게 더 바뀌지 않은 파일만 (복사가 끝난 것) 기록합니다.
function Invoke-FileCheck {
    $now = Get-Date
    foreach ($path in [UsbControlFiles]::Drain()) {
        if ($path.StartsWith('?')) {
            $root = $path.Substring(1)
            $device = Find-WatchedDevice $root
            if ($device -and (-not $missedReported.ContainsKey($root) -or ($now - $missedReported[$root]).TotalMinutes -ge 1)) {
                Add-UsbEvent '파일 반출 기록 누락' $device $root
                $missedReported[$root] = $now
            }
            continue
        }
        $pendingFiles[$path] = $now
    }

    foreach ($path in @($pendingFiles.Keys)) {
        if (($now - $pendingFiles[$path]).TotalSeconds -lt $CheckSeconds) { continue }
        $pendingFiles.Remove($path)
        $device = Find-WatchedDevice ([IO.Path]::GetPathRoot($path))
        $file = Get-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
        # 폴더, 금방 지워진 임시 파일, 이미 뽑은 USB는 건너뜁니다.
        if (-not $device -or -not $file -or $file.PSIsContainer) { continue }
        $last = $loggedFiles[$path]
        if ($last -and $last.Size -eq $file.Length -and ($now - $last.Time).TotalMinutes -lt 10) { continue }
        $loggedFiles[$path] = @{ Size = $file.Length; Time = $now }
        Add-UsbEvent '파일 반출' $device $path $file.Length
    }

    if ($loggedFiles.Count -gt 5000) { $script:loggedFiles = @{} }
}

function Find-WatchedDevice([string]$Root) {
    foreach ($w in $watched.Values) {
        if ($w.Roots -contains $Root) { return $w.Device }
    }
    return $null
}

# 지정 포트의 키보드·마우스 확인. 지정 포트가 하나도 없으면 꽂힌 목록만 서버에 알립니다.
function Invoke-InputCheck {
    $current = @(Get-InputDevices)
    $script:inputDevices = $current
    $rules = @($policy.ports)

    $state    = @{}
    $missing  = @{}
    $assigned = @{}
    foreach ($r in $rules) {
        $key = "$($r.kind)|$($r.port)"
        $assigned[$key] = $true
        $prev = $portState[$key]
        $d = $current | Where-Object { $_.Kind -eq $r.kind -and $_.Port -eq $r.port } | Select-Object -First 1
        if (-not $d) {
            # 잠깐 끊겼다 붙는 경우를 빼려고 두 번 연속(약 4초) 비어 있어야 '빠짐'으로 봅니다.
            $missing[$key] = [int]$portMissing[$key] + 1
            if ($missing[$key] -ge 2 -and $prev -ne 'missing') {
                Add-UsbEvent '빠짐' ([pscustomobject]@{ Kind = $r.kind; Name = $r.deviceName; InstanceId = $r.deviceId; PortLabel = $r.portLabel })
                $prev = 'missing'
            }
            $state[$key] = $prev
        } elseif ($r.deviceId -and $d.InstanceId -ne $r.deviceId) {
            # 같은 포트에 다른 장치 (중간에 다른 장치를 끼웠거나 바꿔 꽂음)
            if ($prev -ne 'changed') { Add-UsbEvent '다른 장치로 바뀜' $d }
            $state[$key] = 'changed'
        } else {
            if ($prev -eq 'missing' -or $prev -eq 'changed') { Add-UsbEvent '다시 연결' $d }
            $state[$key] = 'ok'
        }
    }
    $script:portState   = $state
    $script:portMissing = $missing

    $extra = @{}
    if ($rules.Count -gt 0) {
        foreach ($d in $current) {
            if ($assigned.ContainsKey("$($d.Kind)|$($d.Port)")) { continue }
            $key = "$($d.Kind)|$($d.Port)|$($d.InstanceId)"
            $extra[$key] = $d
            if (-not $extraInputs.ContainsKey($key)) { Add-UsbEvent '지정 외 포트에 연결' $d }
        }
        foreach ($key in $extraInputs.Keys) {
            if (-not $extra.ContainsKey($key)) { Add-UsbEvent '지정 외 포트에서 빠짐' $extraInputs[$key] }
        }
    }
    $script:extraInputs = $extra
}

function Send-BlockedMessage($Device) {
    Send-UserMessage "허용되지 않은 $($Device.Kind)를 차단했습니다.`n$($Device.Name)`n`n사용하려면 관리자에게 등록을 요청하세요."
}

# 시작하자마자 저장된 정책을 다시 걸어 둡니다. (누가 레지스트리를 지웠어도 복구)
try { Set-InstallBlock $policy.installBlock $policy.allow } catch { Write-ErrorLog "설치 단계 차단 적용 실패: $($_.Exception.Message)" }

while ($true) {
    # 키보드·마우스 확인이 실패해도 USB 차단은 계속되도록 따로 처리합니다.
    try {
        Invoke-InputCheck
        $inputErrorShown = $false
    } catch {
        if (-not $inputErrorShown) { Write-ErrorLog "키보드·마우스 확인 중 오류: $($_.Exception.Message)" }
        $inputErrorShown = $true
    }
    try {
        if (((Get-Date) - $lastSync).TotalSeconds -ge $SyncSeconds) {
            $lastSync = Get-Date
            Invoke-Sync
        }
        Invoke-DeviceCheck
    } catch {
        Write-ErrorLog "확인 중 오류: $($_.Exception.Message)"
    }
    try {
        Invoke-FileCheck
    } catch {
        Write-ErrorLog "파일 반출 확인 중 오류: $($_.Exception.Message)"
    }
    Start-Sleep -Seconds $CheckSeconds
}
