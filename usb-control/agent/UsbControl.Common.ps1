# 여러 스크립트가 같이 쓰는 설정과 함수입니다. 다른 스크립트에서 점(.)으로 불러서 씁니다.

$AgentVersion    = '2.2.0'
$InstallDir      = Join-Path $env:ProgramData 'UsbControl'
$ConfigPath      = Join-Path $InstallDir 'config.json'     # 서버 주소와 접속 키 (관리자만 읽기 가능)
$PolicyCachePath = Join-Path $InstallDir 'policy.json'     # 서버에서 마지막으로 받은 정책 (서버가 꺼져도 이걸로 차단)
$QueuePath       = Join-Path $InstallDir 'queue.jsonl'     # 아직 서버로 못 보낸 기록
$LogDir          = Join-Path $InstallDir 'logs'
$LogPath         = Join-Path $LogDir 'usb-log.csv'         # 이 PC 기록 사본
$ErrorLogPath    = Join-Path $LogDir 'error.log'

$RestrictionsKey = 'HKLM:\SOFTWARE\Policies\Microsoft\Windows\DeviceInstall\Restrictions'
$DiskDriveClass  = '{4d36e967-e325-11ce-bfc1-08002be10318}'   # 장치 관리자의 '디스크 드라이브' 종류

# 개인정보처리 PC의 읽기 전용: 윈도우 '이동식 저장소 액세스' 정책의 '쓰기 권한 거부'
$StorageDevicesKey   = 'HKLM:\SOFTWARE\Policies\Microsoft\Windows\RemovableStorageDevices'
$WriteProtectMarker  = 'UsbControlWriteProtect'   # 이 프로그램이 건 설정인지 표시 (회사 그룹 정책으로 건 것은 건드리지 않음)
$WriteProtectClasses = @(
    '{53f5630d-b6bf-11d0-94f2-00a0c91efb8b}'   # 이동식 디스크 (USB 메모리, 외장하드, 카드리더)
    '{53f56308-b6bf-11d0-94f2-00a0c91efb8b}'   # CD/DVD
    '{6AC27878-A6FA-4155-BA85-F98F491D4F33}'   # 휴대폰 등 휴대용 장치(WPD)
    '{F33FDC04-D1AC-4E8E-9A30-19BBD4B108AE}'   # 휴대폰 등 휴대용 장치(WPD)
)

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

# ---------------------------------------------------------------- 키보드·마우스 포트

# 2초마다 확인하므로 Get-PnpDeviceProperty(한 번에 2초 넘게 걸림) 대신 윈도우 장치 관리 함수를 직접 부릅니다.
if (-not ('UsbControlDevices' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Text;

public static class UsbControlDevices {
    [StructLayout(LayoutKind.Sequential)]
    struct DEVPROPKEY { public Guid fmtid; public uint pid; }

    [DllImport("cfgmgr32.dll", CharSet = CharSet.Unicode)]
    static extern int CM_Get_Device_ID_List_SizeW(out uint len, string filter, uint flags);
    [DllImport("cfgmgr32.dll", CharSet = CharSet.Unicode)]
    static extern int CM_Get_Device_ID_ListW(string filter, char[] buffer, uint len, uint flags);
    [DllImport("cfgmgr32.dll", CharSet = CharSet.Unicode)]
    static extern int CM_Locate_DevNodeW(out uint devInst, string id, uint flags);
    [DllImport("cfgmgr32.dll")]
    static extern int CM_Get_Parent(out uint parent, uint devInst, uint flags);
    [DllImport("cfgmgr32.dll", CharSet = CharSet.Unicode)]
    static extern int CM_Get_Device_IDW(uint devInst, StringBuilder buffer, int len, uint flags);
    [DllImport("cfgmgr32.dll", CharSet = CharSet.Unicode)]
    static extern int CM_Get_DevNode_PropertyW(uint devInst, ref DEVPROPKEY key, out uint type, byte[] buffer, ref uint size, uint flags);

    const uint FILTER_PRESENT = 0x100, FILTER_CLASS = 0x200;
    const int CR_BUFFER_SMALL = 0x1A;

    // 지금 꽂혀 있는 장치 중 이 종류(장치 클래스 GUID)인 것의 ID
    public static string[] PresentIds(string classGuid) {
        for (int attempt = 0; attempt < 3; attempt++) {
            uint len;
            if (CM_Get_Device_ID_List_SizeW(out len, classGuid, FILTER_CLASS | FILTER_PRESENT) != 0) break;
            char[] buffer = new char[len];
            int cr = CM_Get_Device_ID_ListW(classGuid, buffer, len, FILTER_CLASS | FILTER_PRESENT);
            if (cr == CR_BUFFER_SMALL) continue;
            if (cr != 0) break;
            return new string(buffer).Split(new[] { '\0' }, StringSplitOptions.RemoveEmptyEntries);
        }
        return new string[0];
    }

    // 부모 장치 ID. 장치가 빠졌으면 null
    public static string Parent(string id) {
        uint dev, parent;
        if (CM_Locate_DevNodeW(out dev, id, 0) != 0 || CM_Get_Parent(out parent, dev, 0) != 0) return null;
        StringBuilder sb = new StringBuilder(512);
        return CM_Get_Device_IDW(parent, sb, sb.Capacity, 0) == 0 ? sb.ToString() : null;
    }

    // 문자열(목록) 속성. 없으면 빈 배열
    public static string[] Property(string id, string fmtid, uint pid) {
        uint dev, type, size = 0;
        if (CM_Locate_DevNodeW(out dev, id, 0) != 0) return new string[0];
        DEVPROPKEY key = new DEVPROPKEY { fmtid = new Guid(fmtid), pid = pid };
        if (CM_Get_DevNode_PropertyW(dev, ref key, out type, null, ref size, 0) != CR_BUFFER_SMALL) return new string[0];
        byte[] buffer = new byte[size];
        if (CM_Get_DevNode_PropertyW(dev, ref key, out type, buffer, ref size, 0) != 0) return new string[0];
        if (type != 0x12 && type != 0x2012) return new string[0];   // DEVPROP_TYPE_STRING, DEVPROP_TYPE_STRING_LIST
        return Encoding.Unicode.GetString(buffer, 0, (int)size).Split(new[] { '\0' }, StringSplitOptions.RemoveEmptyEntries);
    }
}
'@
}

# 장치 관리자의 '키보드' / '마우스 및 기타 포인팅 장치' 종류
$InputClasses = [ordered]@{ '키보드' = '{4d36e96b-e325-11ce-bfc1-08002be10318}'; '마우스' = '{4d36e96f-e325-11ce-bfc1-08002be10318}' }

function Get-NativeProperty([string]$InstanceId, [string]$FmtId, [int]$PropertyId) {
    @([UsbControlDevices]::Property($InstanceId, $FmtId, $PropertyId))
}

# 키보드·마우스 항목(HID\...)의 부모를 따라 올라가 USB 포트에 꽂힌 장치 본체(USB\VID_...)를 찾습니다.
# 노트북 내장 키보드, 블루투스, 원격 데스크톱 같은 USB가 아닌 장치는 $null 입니다.
function Get-UsbDeviceOf([string]$InstanceId) {
    $id = $InstanceId
    for ($i = 0; $i -lt 8; $i++) {
        $id = [UsbControlDevices]::Parent($id)
        if (-not $id -or $id -like 'BTH*' -or $id -like 'ACPI\*' -or $id -like 'PCI\*' -or $id -like 'ROOT\*' -or $id -like 'HTREE\*' -or $id -like 'SWD\*') {
            return $null
        }
        # 여러 기능을 가진 장치는 USB\VID_...&MI_00 (기능 하나) 위에 장치 본체가 있습니다.
        if ($id -like 'USB\VID_*' -and $id -notlike '*&MI_*') { return $id }
    }
    return $null
}

# 'Port_#0003.Hub_#0001' → '허브 1 - 포트 3'
function Format-PortLabel([string]$LocationInfo) {
    if ($LocationInfo -match 'Port_#0*(\d+)\.Hub_#0*(\d+)') { return "허브 $($Matches[2]) - 포트 $($Matches[1])" }
    return $LocationInfo
}

# 지금 USB에 꽂혀 있는 키보드·마우스와 꽂힌 포트
function Get-InputDevices {
    $found = @{}
    foreach ($kind in $InputClasses.Keys) {
        foreach ($hid in [UsbControlDevices]::PresentIds($InputClasses[$kind])) {
            $usb = Get-UsbDeviceOf $hid
            if (-not $usb -or $found.ContainsKey("$kind|$usb")) { continue }
            $found["$kind|$usb"] = $true

            # 포트는 매번 새로 읽습니다. 일련번호가 있는 장치는 다른 포트로 옮겨도 장치 ID가 그대로입니다.
            $paths = Get-NativeProperty $usb '{a45c254e-df1c-4efd-8020-67d146a850e0}' 37   # DEVPKEY_Device_LocationPaths
            $info  = Get-NativeProperty $usb '{a45c254e-df1c-4efd-8020-67d146a850e0}' 15   # DEVPKEY_Device_LocationInfo
            $desc  = Get-NativeProperty $usb '{540b947e-8b40-45bc-a8a2-6a0b894cbda2}' 4    # DEVPKEY_Device_BusReportedDeviceDesc
            $name  = Get-NativeProperty $hid '{b725f130-47ef-101a-a5f1-02608c9eebac}' 10   # DEVPKEY_NAME
            $infoText = [string]($info | Select-Object -First 1)
            [pscustomobject]@{
                Kind       = $kind
                Name       = [string](@($desc) + @($name) | Select-Object -First 1)
                InstanceId = $usb
                # 포트 위치 (예: PCIROOT(0)#PCI(1400)#USBROOT(0)#USB(3)). 없으면 허브 ID와 포트 번호로 대신합니다.
                Port       = if ($paths.Count -gt 0) { [string]$paths[0] } else { "$([UsbControlDevices]::Parent($usb))|$infoText" }
                PortLabel  = Format-PortLabel $infoText
            }
        }
    }
}

# ---------------------------------------------------------------- 파일 반출 기록 (개인정보처리 PC)

# USB 드라이브에 새로 생기거나 바뀐 파일을 모아 둡니다. 윈도우가 다른 스레드에서 알려 주므로 대기열에 넣어 두고 2초마다 꺼냅니다.
if (-not ('UsbControlFiles' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;

public static class UsbControlFiles {
    static readonly Dictionary<string, FileSystemWatcher> watchers = new Dictionary<string, FileSystemWatcher>(StringComparer.OrdinalIgnoreCase);
    static readonly ConcurrentQueue<string> changed = new ConcurrentQueue<string>();

    public static void Watch(string root) {
        if (watchers.ContainsKey(root)) return;
        FileSystemWatcher w = new FileSystemWatcher(root);
        w.IncludeSubdirectories = true;
        w.InternalBufferSize = 64 * 1024;
        w.NotifyFilter = NotifyFilters.FileName | NotifyFilters.Size | NotifyFilters.LastWrite;
        w.Created += (s, e) => changed.Enqueue(e.FullPath);
        w.Changed += (s, e) => changed.Enqueue(e.FullPath);
        w.Renamed += (s, e) => changed.Enqueue(e.FullPath);
        // 한꺼번에 너무 많이 바뀌어 알림을 놓치면 '?드라이브' 로 알립니다. (USB를 뽑아서 나는 오류는 무시)
        w.Error += (s, e) => { if (e.GetException() is InternalBufferOverflowException) changed.Enqueue("?" + root); };
        w.EnableRaisingEvents = true;
        watchers[root] = w;
    }

    public static void Unwatch(string root) {
        FileSystemWatcher w;
        if (!watchers.TryGetValue(root, out w)) return;
        watchers.Remove(root);
        try { w.EnableRaisingEvents = false; } catch { }
        w.Dispose();
    }

    public static string[] Drain() {
        List<string> list = new List<string>();
        string path;
        while (changed.TryDequeue(out path)) list.Add(path);
        return list.ToArray();
    }
}
'@
}

# USB 저장장치(USBSTOR\... / SCSI\DISK...)의 드라이브 문자 (예: E:\)
function Get-DriveRoots([string]$InstanceId) {
    try {
        foreach ($disk in Get-CimInstance -ClassName Win32_DiskDrive -ErrorAction Stop) {
            if ($disk.PNPDeviceID -ne $InstanceId) { continue }
            foreach ($part in Get-CimAssociatedInstance -InputObject $disk -ResultClassName Win32_DiskPartition) {
                foreach ($volume in Get-CimAssociatedInstance -InputObject $part -ResultClassName Win32_LogicalDisk) {
                    "$($volume.DeviceID)\"
                }
            }
        }
    } catch { }
}

# 저장장치·휴대폰 쓰기 금지. 꽂혀 있는 장치는 다시 연결해야 적용됩니다.
function Set-WriteProtect([bool]$Enabled) {
    if ($Enabled) {
        foreach ($class in $WriteProtectClasses) {
            $path = Join-Path $StorageDevicesKey $class
            if (-not (Test-Path $path)) { New-Item -Path $path -Force | Out-Null }
            Set-ItemProperty -Path $path -Name 'Deny_Write' -Value 1 -Type DWord
        }
        Set-ItemProperty -Path $StorageDevicesKey -Name $WriteProtectMarker -Value 1 -Type DWord
    } elseif (Test-WriteProtect) {
        foreach ($class in $WriteProtectClasses) {
            Remove-ItemProperty -Path (Join-Path $StorageDevicesKey $class) -Name 'Deny_Write' -ErrorAction SilentlyContinue
        }
        Remove-ItemProperty -Path $StorageDevicesKey -Name $WriteProtectMarker -ErrorAction SilentlyContinue
    }
}

function Test-WriteProtect {
    $null -ne (Get-ItemProperty -Path $StorageDevicesKey -Name $WriteProtectMarker -ErrorAction SilentlyContinue)
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
        # 개인정보처리 PC: 저장장치는 읽기 전용(writable 에 있는 매체만 쓰기), USB로 복사한 파일 기록
        privacyPc    = [bool]$Raw.privacyPc
        writable     = @($Raw.writable | Where-Object { $_ } | ForEach-Object { ([string]$_).Trim() })
        # 키보드·마우스를 꽂아 두어야 하는 지정 포트
        ports        = @($Raw.ports | Where-Object { $_ -and $_.port } | ForEach-Object {
            [pscustomobject]@{
                kind       = [string]$_.kind
                port       = [string]$_.port
                portLabel  = [string]$_.portLabel
                deviceId   = [string]$_.deviceId
                deviceName = [string]$_.deviceName
            }
        })
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

# 서버에 상태를 알리고 최신 정책을 받아 옵니다. 지금 꽂혀 있는 키보드·마우스도 같이 알립니다.
function Invoke-Checkin($Config, [string]$AppliedVersion, $InputDevices = @()) {
    $response = Invoke-Server $Config '/api/agent/checkin' @{
        pcName        = $env:COMPUTERNAME
        userName      = Get-LoggedOnUser
        agentVersion  = $AgentVersion
        policyVersion = $AppliedVersion
        os            = Get-OsName
        inputDevices  = @($InputDevices | ForEach-Object {
            @{ kind = $_.Kind; name = $_.Name; deviceId = $_.InstanceId; port = $_.Port; portLabel = $_.PortLabel }
        })
    }
    ConvertTo-Policy $response
}

# 기록을 대기열에 넣습니다. 서버로는 Send-QueuedEvents 가 모아서 보냅니다.
# FileName, FileSize: 개인정보처리 PC에서 USB로 복사한 파일
function Add-UsbEvent([string]$Action, $Device, [string]$FileName = '', $FileSize = $null) {
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
        port       = [string]$Device.PortLabel
        fileName   = $FileName
        fileSize   = $FileSize
    }
    Add-Content -Path $QueuePath -Value ($item | ConvertTo-Json -Compress) -Encoding UTF8

    # 이 PC에도 사본을 남깁니다.
    [pscustomobject]@{
        시간   = $now.ToString('yyyy-MM-dd HH:mm:ss')
        동작   = $Action
        종류   = $item.kind
        이름   = if ($item.port) { "$($item.deviceName) [$($item.port)]" }
                 elseif ($FileName) { "$($item.deviceName) → $FileName ($FileSize 바이트)" }
                 else { $item.deviceName }
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
