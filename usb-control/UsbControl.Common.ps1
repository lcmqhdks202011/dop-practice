# 여러 스크립트가 같이 쓰는 설정과 함수입니다. 다른 스크립트에서 점(.)으로 불러서 씁니다.

$InstallDir    = Join-Path $env:ProgramData 'UsbControl'
$AllowListPath = Join-Path $InstallDir 'allowlist.txt'
$LogDir        = Join-Path $InstallDir 'logs'
$LogPath       = Join-Path $LogDir 'usb-log.csv'
$ErrorLogPath  = Join-Path $LogDir 'error.log'

$CM_PROB_DISABLED = 22   # 장치 관리자에서 '사용 안 함' 상태

$ParentCache = @{}

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

# 예전에 꽂았다가 지금은 빠져 있는 장치까지 포함한 목록 (정리할 때만 씁니다)
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

# 허용 목록 읽기. 빈 줄과 # 으로 시작하는 줄(메모)은 건너뜁니다.
function Read-AllowList {
    if (-not (Test-Path $AllowListPath)) { return @() }
    Get-Content -Path $AllowListPath -Encoding UTF8 |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ -and -not $_.StartsWith('#') }
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

function Add-ToAllowList($Device) {
    if (-not (Test-Path $InstallDir)) { New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null }
    $memo = "# $($Device.Kind) - $($Device.Name) ($((Get-Date).ToString('yyyy-MM-dd')) 등록)"
    Add-Content -Path $AllowListPath -Value $memo, $Device.InstanceId -Encoding UTF8
}

function Write-UsbLog([string]$Action, $Device) {
    if (-not (Test-Path $LogDir)) { New-Item -ItemType Directory -Path $LogDir -Force | Out-Null }
    $user = ''
    try { $user = (Get-CimInstance -ClassName Win32_ComputerSystem).UserName } catch { }
    [pscustomobject]@{
        시간   = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
        동작   = $Action
        종류   = $Device.Kind
        이름   = $Device.Name
        사용자 = $user
        장치ID = $Device.InstanceId
    } | Export-Csv -Path $LogPath -Append -NoTypeInformation -Encoding UTF8
}

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
