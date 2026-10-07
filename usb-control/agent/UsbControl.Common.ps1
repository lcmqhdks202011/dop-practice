# 여러 스크립트가 같이 쓰는 설정과 함수입니다. 다른 스크립트에서 점(.)으로 불러서 씁니다.

$AgentVersion    = '2.0.0'
$InstallDir      = Join-Path $env:ProgramData 'UsbControl'
$ConfigPath      = Join-Path $InstallDir 'config.json'     # 서버 주소와 접속 키 (관리자만 읽기 가능)
$PolicyCachePath = Join-Path $InstallDir 'policy.json'     # 서버에서 마지막으로 받은 정책 (서버가 꺼져도 이걸로 차단)
$QueuePath       = Join-Path $InstallDir 'queue.jsonl'     # 아직 서버로 못 보낸 기록
$LogDir          = Join-Path $InstallDir 'logs'
$LogPath         = Join-Path $LogDir 'usb-log.csv'         # 이 PC 기록 사본
$ErrorLogPath    = Join-Path $LogDir 'error.log'

$RestrictionsKey = 'HKLM:\SOFTWARE\Policies\Microsoft\Windows\DeviceInstall\Restrictions'
$DiskDriveClass  = '{4d36e967-e325-11ce-bfc1-08002be10318}'   # 장치 관리자의 '디스크 드라이브' 종류

$CM_PROB_DISABLED = 22   # 장치 관리자에서 '사용 안 함' 상태

$ParentCache = @{}

# ---------------------------------------------------------------- 장치 찾기

# 장치의 부모 장치 ID. 한 번 찾은 값은 기억해 둡니다.
function Get-DeviceParent([string]$InstanceId) {
    if (-not $ParentCache.ContainsKey($InstanceId)) {
        $parent = ''
        try {
            $parent = (Get-PnpDeviceProperty -InstanceId $InstanceId -KeyName 'DEVPKEY_Device_Parent' -ErrorAction Stop).Data
        } catch { }
        $ParentCache[$InstanceId] = [string]$parent
    }
    return $ParentCache[$InstanceId]
}

# 통제 대상이면 종류('USB저장장치' / '휴대폰')를, 아니면 $null 을 돌려줍니다.
function Get-DeviceKind([string]$InstanceId, [string]$Class, [bool]$IncludePhones) {
    # 일반 USB 메모리, 외장하드, 카드리더, USB DVD
    if ($InstanceId -like 'USBSTOR\*') { return 'USB저장장치' }
    # 빠른 전송 방식(UAS) 외장 SSD. 내장 디스크도 SCSI\DISK 로 시작하므로 부모가 USB 인지 확인합니다.
    if ($InstanceId -like 'SCSI\DISK*' -and (Get-DeviceParent $InstanceId) -like 'USB\*') { return 'USB저장장치' }
    # 휴대폰 파일 전송(MTP). USB 메모리가 만드는 '휴대용 장치' 항목(SWD\...)은 제외됩니다.
    if ($IncludePhones -and $Class -eq 'WPD' -and $InstanceId -like 'USB\*') { return '휴대폰' }
    return $null
}

function New-DeviceInfo($Kind, $Name, $InstanceId, $ErrorCode) {
    [pscustomobject]@{
        Kind       = $Kind
        Name       = [string]$Name
        InstanceId = [string]$InstanceId
        ErrorCode  = [int]$ErrorCode
        Disabled   = ([int]$ErrorCode -eq $CM_PROB_DISABLED)
    }
}

# 지금 꽂혀 있는 통제 대상 장치 목록
function Get-ControlledDevices([switch]$IncludePhones) {
    $filter = "DeviceID LIKE 'USBSTOR\\%' OR DeviceID LIKE 'SCSI\\DISK%' OR PNPClass = 'WPD'"
    foreach ($e in Get-CimInstance -ClassName Win32_PnPEntity -Filter $filter) {
        $kind = Get-DeviceKind $e.DeviceID $e.PNPClass $IncludePhones
        if ($kind) { New-DeviceInfo $kind $e.Name $e.DeviceID $e.ConfigManagerErrorCode }
    }
}

# 예전에 꽂았다가 지금은 빠져 있는 장치까지 포함한 목록 (제거할 때만 씁니다)
function Get-AllControlledDevices([switch]$IncludePhones) {
    $presentIds = @{}
    $present = @(Get-ControlledDevices -IncludePhones:$IncludePhones)
    foreach ($d in $present) { $presentIds[$d.InstanceId] = $true }
    $present

    foreach ($p in Get-PnpDevice) {
        if ($presentIds.ContainsKey($p.InstanceId)) { continue }
        $kind = Get-DeviceKind $p.InstanceId $p.Class $IncludePhones
        if ($kind) {
            $info = New-DeviceInfo $kind $p.FriendlyName $p.InstanceId 0
            $info | Add-Member -NotePropertyName Absent -NotePropertyValue $true
            $info
        }
    }
}

function Test-Allowed([string]$InstanceId, [string[]]$AllowList) {
    foreach ($entry in $AllowList) {
        if ($entry.Contains('*')) {
            if ($InstanceId -like $entry) { return $true }
        } elseif ($InstanceId -eq $entry) {
            return $true
        }
    }
    return $false
}

# ---------------------------------------------------------------- 설정과 정책

function Read-Config {
    if (-not (Test-Path $ConfigPath)) { return $null }
    Get-Content -Path $ConfigPath -Raw -Encoding UTF8 | ConvertFrom-Json
}

function Save-Config([string]$ServerUrl, [string]$AgentKey) {
    if (-not (Test-Path $InstallDir)) { New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null }
    [pscustomobject]@{ serverUrl = $ServerUrl; agentKey = $AgentKey } |
        ConvertTo-Json | Set-Content -Path $ConfigPath -Encoding UTF8
    # 일반 사용자가 접속 키를 읽지 못하게 시스템과 관리자만 접근
    & icacls $ConfigPath /inheritance:r /grant:r '*S-1-5-18:F' '*S-1-5-32-544:F' | Out-Null
}

# 서버 응답(또는 저장된 파일)을 정책 객체로 정리합니다.
function ConvertTo-Policy($Raw) {
    [pscustomobject]@{
        version      = [string]$Raw.version
        blockPhones  = [bool]$Raw.blockPhones
        notifyUser   = [bool]$Raw.notifyUser
        installBlock = [bool]$Raw.installBlock
        allow        = @($Raw.allow | Where-Object { $_ } | ForEach-Object { ([string]$_).Trim() })
    }
}

# 서버에서 아무것도 못 받았을 때: 전부 차단
function Get-DefaultPolicy {
    ConvertTo-Policy ([pscustomobject]@{ version = ''; blockPhones = $true; notifyUser = $true; installBlock = $false; allow = @() })
}

function Read-CachedPolicy {
    try {
        if (Test-Path $PolicyCachePath) {
            return ConvertTo-Policy (Get-Content -Path $PolicyCachePath -Raw -Encoding UTF8 | ConvertFrom-Json)
        }
    } catch {
        Write-ErrorLog "저장된 정책을 읽지 못함: $($_.Exception.Message)"
    }
    return Get-DefaultPolicy
}

function Save-CachedPolicy($Policy) {
    $Policy | ConvertTo-Json -Depth 5 | Set-Content -Path $PolicyCachePath -Encoding UTF8
}

# 설치 단계 차단: 윈도우 '장치 설치 제한' 정책으로 미등록 디스크의 설치 자체를 막습니다.
# 별표(*)가 들어간 허용 항목은 이 정책에 쓸 수 없어 빠집니다.
function Set-InstallBlock([bool]$Enabled, [string[]]$AllowList) {
    # '허용이 금지보다 우선' 설정은 윈도우 10 2004(빌드 19041) 이후부터 됩니다.
    if ($Enabled -and [Environment]::OSVersion.Version.Build -lt 19041) {
        Write-ErrorLog '윈도우가 오래되어 설치 단계 차단을 쓸 수 없습니다. (윈도우 10 2004 이상 필요)'
        $Enabled = $false
    }
    if (-not $Enabled) {
        Remove-InstallBlock
        return
    }

    $exact = @($AllowList | Where-Object { -not $_.Contains('*') })
    New-Item -Path $RestrictionsKey -Force | Out-Null
    # 모든 장치 일치 조건에 대해 허용/금지를 계층 순서로 평가 (장치 ID 허용이 종류 금지보다 우선)
    Set-ItemProperty -Path $RestrictionsKey -Name 'AllowDenyLayered'             -Value 1 -Type DWord
    # 이 장치 종류와 일치하는 장치 설치 금지
    Set-ItemProperty -Path $RestrictionsKey -Name 'DenyDeviceClasses'            -Value 1 -Type DWord
    # 1로 하면 내장 디스크까지 지워지므로 절대 켜지 않음
    Set-ItemProperty -Path $RestrictionsKey -Name 'DenyDeviceClassesRetroactive' -Value 0 -Type DWord
    Set-RegistryList 'DenyDeviceClasses' @($DiskDriveClass)
    # 이 장치 ID와 일치하는 장치 설치 허용
    Set-ItemProperty -Path $RestrictionsKey -Name 'AllowInstanceIDs'             -Value 1 -Type DWord
    Set-RegistryList 'AllowInstanceIDs' $exact
}

function Remove-InstallBlock {
    if (-not (Test-Path $RestrictionsKey)) { return }
    foreach ($name in 'AllowDenyLayered', 'DenyDeviceClasses', 'DenyDeviceClassesRetroactive', 'AllowInstanceIDs') {
        Remove-ItemProperty -Path $RestrictionsKey -Name $name -ErrorAction SilentlyContinue
    }
    foreach ($name in 'DenyDeviceClasses', 'AllowInstanceIDs') {
        Remove-Item -Path (Join-Path $RestrictionsKey $name) -Recurse -ErrorAction SilentlyContinue
    }
}

function Set-RegistryList([string]$Name, [string[]]$Values) {
    $path = Join-Path $RestrictionsKey $Name
    if (Test-Path $path) { Remove-Item -Path $path -Recurse }
    New-Item -Path $path -Force | Out-Null
    for ($i = 0; $i -lt $Values.Count; $i++) {
        New-ItemProperty -Path $path -Name ([string]($i + 1)) -Value $Values[$i] -PropertyType String | Out-Null
    }
}

# ---------------------------------------------------------------- 서버 통신

function Invoke-Server($Config, [string]$Path, $Body) {
    $json  = $Body | ConvertTo-Json -Depth 6 -Compress
    $bytes = [Text.Encoding]::UTF8.GetBytes($json)
    Invoke-RestMethod -Uri ($Config.serverUrl.TrimEnd('/') + $Path) -Method Post -Body $bytes `
        -ContentType 'application/json; charset=utf-8' -Headers @{ 'X-Agent-Key' = $Config.agentKey } `
        -TimeoutSec 10 -UseBasicParsing
}

function Get-LoggedOnUser {
    try { return [string](Get-CimInstance -ClassName Win32_ComputerSystem).UserName } catch { return '' }
}

function Get-OsName {
    try {
        $os = Get-CimInstance -ClassName Win32_OperatingSystem
        return "$($os.Caption) (빌드 $($os.BuildNumber))"
    } catch { return '' }
}

# 서버에 상태를 알리고 최신 정책을 받아 옵니다.
function Invoke-Checkin($Config, [string]$AppliedVersion) {
    $response = Invoke-Server $Config '/api/agent/checkin' @{
        pcName        = $env:COMPUTERNAME
        userName      = Get-LoggedOnUser
        agentVersion  = $AgentVersion
        policyVersion = $AppliedVersion
        os            = Get-OsName
    }
    ConvertTo-Policy $response
}

# 기록을 대기열에 넣습니다. 서버로는 Send-QueuedEvents 가 모아서 보냅니다.
function Add-UsbEvent([string]$Action, $Device) {
    if (-not (Test-Path $LogDir)) { New-Item -ItemType Directory -Path $LogDir -Force | Out-Null }
    $user = Get-LoggedOnUser
    $now  = Get-Date
    $item = [pscustomobject]@{
        occurredAt = $now.ToString('s')
        userName   = $user
        action     = $Action
        kind       = [string]$Device.Kind
        deviceName = [string]$Device.Name
        instanceId = [string]$Device.InstanceId
    }
    Add-Content -Path $QueuePath -Value ($item | ConvertTo-Json -Compress) -Encoding UTF8

    # 이 PC에도 사본을 남깁니다.
    [pscustomobject]@{
        시간   = $now.ToString('yyyy-MM-dd HH:mm:ss')
        동작   = $Action
        종류   = $item.kind
        이름   = $item.deviceName
        사용자 = $user
        장치ID = $item.instanceId
    } | Export-Csv -Path $LogPath -Append -NoTypeInformation -Encoding UTF8
}

# 대기열의 기록을 서버로 보냅니다. 실패하면 남겨 두었다가 다음에 다시 보냅니다.
function Send-QueuedEvents($Config) {
    if (-not (Test-Path $QueuePath)) { return }
    $lines = @(Get-Content -Path $QueuePath -Encoding UTF8 | Where-Object { $_.Trim() })
    if ($lines.Count -eq 0) { Remove-Item -Path $QueuePath; return }

    $batchSize = 500
    for ($start = 0; $start -lt $lines.Count; $start += $batchSize) {
        $end   = [Math]::Min($start + $batchSize, $lines.Count) - 1
        $batch = @($lines[$start..$end] | ForEach-Object {
            try { $_ | ConvertFrom-Json } catch { Write-ErrorLog "깨진 기록을 건너뜀: $_" }
        })
        try {
            Invoke-Server $Config '/api/agent/events' @{ pcName = $env:COMPUTERNAME; events = $batch } | Out-Null
        } catch {
            # 못 보낸 것부터 다시 저장
            Set-Content -Path $QueuePath -Value $lines[$start..($lines.Count - 1)] -Encoding UTF8
            throw
        }
    }
    Remove-Item -Path $QueuePath
}

# ---------------------------------------------------------------- 기타

function Write-ErrorLog([string]$Message) {
    if (-not (Test-Path $LogDir)) { New-Item -ItemType Directory -Path $LogDir -Force | Out-Null }
    Add-Content -Path $ErrorLogPath -Value "$((Get-Date).ToString('yyyy-MM-dd HH:mm:ss')) $Message" -Encoding UTF8
}

# 로그인한 사용자 화면에 알림 창 띄우기 (윈도우 Home 에는 msg.exe 가 없어 조용히 넘어갑니다)
function Send-UserMessage([string]$Text) {
    $msg = Join-Path $env:SystemRoot 'System32\msg.exe'
    if (Test-Path $msg) {
        try { & $msg '*' '/TIME:15' $Text 2>$null | Out-Null } catch { }
    }
}
