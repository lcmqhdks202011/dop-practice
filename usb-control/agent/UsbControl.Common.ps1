# 여러 스크립트가 같이 쓰는 설정과 함수입니다. 다른 스크립트에서 점(.)으로 불러서 씁니다.

$AgentVersion    = '2.4.0'
$InstallDir      = Join-Path $env:ProgramData 'UsbControl'
$ConfigPath      = Join-Path $InstallDir 'config.json'     # 서버 주소와 접속 키 (관리자만 읽기 가능)
$PolicyCachePath = Join-Path $InstallDir 'policy.json'     # 서버에서 마지막으로 받은 정책 (서버가 꺼져도 이걸로 차단)
$QueuePath       = Join-Path $InstallDir 'queue.jsonl'     # 아직 서버로 못 보낸 기록
$UsbStatePath    = Join-Path $InstallDir 'usb-devices.json' # 마지막으로 확인한 USB 장치 목록 (껐다 켜는 동안 바뀐 것도 기록)
$PiStatePath     = Join-Path $InstallDir 'pi-state.json'    # 개인정보 검사를 마지막으로 한 때
$PiResultPath    = Join-Path $InstallDir 'pi-result.json'   # 아직 서버로 못 보낸 개인정보 검사 결과 (건수만, 실제 번호 없음)
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
    [DllImport("cfgmgr32.dll")]
    static extern int CM_Get_Child(out uint child, uint devInst, uint flags);
    [DllImport("cfgmgr32.dll")]
    static extern int CM_Get_Sibling(out uint sibling, uint devInst, uint flags);
    [DllImport("cfgmgr32.dll", CharSet = CharSet.Unicode)]
    static extern int CM_Get_Device_IDW(uint devInst, StringBuilder buffer, int len, uint flags);
    [DllImport("cfgmgr32.dll", CharSet = CharSet.Unicode)]
    static extern int CM_Get_DevNode_PropertyW(uint devInst, ref DEVPROPKEY key, out uint type, byte[] buffer, ref uint size, uint flags);

    const uint FILTER_ENUMERATOR = 0x1, FILTER_PRESENT = 0x100, FILTER_CLASS = 0x200;
    const int CR_BUFFER_SMALL = 0x1A;

    // 지금 꽂혀 있는 장치 중 이 종류(장치 클래스 GUID)인 것의 ID
    public static string[] PresentIds(string classGuid) {
        return IdList(classGuid, FILTER_CLASS | FILTER_PRESENT);
    }

    // 지금 꽂혀 있는 장치 중 이 버스(예: USB)에 붙은 것의 ID
    public static string[] PresentIdsOnBus(string enumerator) {
        return IdList(enumerator, FILTER_ENUMERATOR | FILTER_PRESENT);
    }

    static string[] IdList(string filter, uint flags) {
        for (int attempt = 0; attempt < 3; attempt++) {
            uint len;
            if (CM_Get_Device_ID_List_SizeW(out len, filter, flags) != 0) break;
            char[] buffer = new char[len];
            int cr = CM_Get_Device_ID_ListW(filter, buffer, len, flags);
            if (cr == CR_BUFFER_SMALL) continue;
            if (cr != 0) break;
            return new string(buffer).Split(new[] { '\0' }, StringSplitOptions.RemoveEmptyEntries);
        }
        return new string[0];
    }

    // 이 장치 아래에 달린 장치들의 ID (기능, 드라이버 항목). 다른 USB 장치(허브에 꽂힌 것)는 따라 내려가지 않습니다.
    public static string[] Descendants(string id, int maxDepth) {
        var found = new System.Collections.Generic.List<string>();
        uint dev;
        if (CM_Locate_DevNodeW(out dev, id, 0) != 0) return found.ToArray();
        var level = new System.Collections.Generic.List<uint> { dev };
        for (int depth = 0; depth < maxDepth && level.Count > 0 && found.Count < 50; depth++) {
            var next = new System.Collections.Generic.List<uint>();
            foreach (uint parent in level) {
                uint child;
                if (CM_Get_Child(out child, parent, 0) != 0) continue;
                do {
                    StringBuilder sb = new StringBuilder(512);
                    if (CM_Get_Device_IDW(child, sb, sb.Capacity, 0) != 0) continue;
                    string childId = sb.ToString();
                    if (IsUsbDevice(childId)) continue;
                    found.Add(childId);
                    next.Add(child);
                } while (CM_Get_Sibling(out child, child, 0) == 0);
            }
            level = next;
        }
        return found.ToArray();
    }

    // USB 포트에 꽂힌 장치 본체 (USB\VID_...). 여러 기능을 가진 장치의 기능 하나(&MI_)는 아님
    public static bool IsUsbDevice(string id) {
        return id.StartsWith(@"USB\VID_", StringComparison.OrdinalIgnoreCase) && id.IndexOf("&MI_", StringComparison.OrdinalIgnoreCase) < 0;
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

# ---------------------------------------------------------------- 개인정보 검사

if (-not ('UsbControlPi' -as [type])) {
    Add-Type -ReferencedAssemblies 'System.IO.Compression' -TypeDefinition @'
using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;

// 파일 안의 개인정보를 찾습니다. 찾은 번호는 어디에도 남기지 않고 종류별 건수만 셉니다.
public static class UsbControlPi {
    // 건수 순서: 주민등록번호, 외국인등록번호, 여권번호, 운전면허번호, 카드번호, 휴대폰번호, 이메일, 계좌번호 (서버 PiCounts 와 같음)
    public const int Kinds = 8;
    // 휴대폰번호·이메일은 업무 문서에도 흔해서 합쳐서 이만큼 이상일 때만 개인정보 파일로 봅니다.
    public const int ContactThreshold = 5;
    const long MaxFileBytes = 100L * 1024 * 1024;   // 이보다 큰 파일은 건너뜀
    const int MaxChars = 20 * 1024 * 1024;          // 파일 하나에서 읽는 글자 수
    const int MaxFindings = 5000;
    static readonly string[] TextExt = { ".txt", ".csv", ".tsv", ".log" };
    static readonly string[] ZipExt = { ".xlsx", ".xlsm", ".docx", ".pptx", ".hwpx" };

    public class Result {
        public string Path;
        public long Size;
        public DateTime Modified;
        public int[] Counts = new int[Kinds];
        public string Note;   // 내용을 보지 못한 이유 (암호 걸린 파일 등). 검사했으면 null
        public bool Significant { get { return Note == null && IsSignificant(Counts); } }
    }

    public static bool CanScan(string path) {
        string ext = (Path.GetExtension(path) ?? "").ToLowerInvariant();
        return Array.IndexOf(TextExt, ext) >= 0 || Array.IndexOf(ZipExt, ext) >= 0;
    }

    public static bool IsSignificant(int[] c) {
        return c[0] + c[1] + c[2] + c[3] + c[4] + c[7] > 0 || c[5] + c[6] >= ContactThreshold;
    }

    // ---------------------------------------------------------------- 찾는 규칙

    const RegexOptions Opt = RegexOptions.Compiled | RegexOptions.CultureInvariant;
    // 주민등록번호(뒷자리 첫 숫자 1~4)와 외국인등록번호(5~8). 생년월일이 맞아야 하고, '-' 없이 붙어 있으면 검증 숫자까지 맞아야 셉니다.
    static readonly Regex Rrn = new Regex(@"(?<![0-9])([0-9]{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12][0-9]|3[01]))[ ]?(-?)[ ]?([1-8][0-9]{6})(?![0-9])", Opt);
    // 여권번호 (예: M12345678, M123A4567). 다른 코드와 헷갈리므로 '여권' 같은 말이 가까이 있을 때만
    static readonly Regex Passport = new Regex(@"(?<![A-Za-z0-9])[MSRODG](?:[0-9]{8}|[0-9]{3}[A-Z][0-9]{4})(?![A-Za-z0-9])", Opt);
    static readonly Regex PassportWord = new Regex(@"여권|passport", Opt | RegexOptions.IgnoreCase);
    // 운전면허번호 (예: 11-12-123456-12, 서울 12-123456-12). '-' 없이 붙어 있으면 '면허'가 가까이 있을 때만
    static readonly Regex Driver = new Regex(@"(?<![0-9])(?:(?:1[1-9]|2[0-8])-|(?:서울|부산|경기|강원|충북|충남|전북|전남|경북|경남|제주|대구|인천|광주|대전|울산)[ ]?)[0-9]{2}-[0-9]{6}-[0-9]{2}(?![0-9])", Opt);
    static readonly Regex DriverPlain = new Regex(@"(?<![0-9])(?:1[1-9]|2[0-8])[0-9]{10}(?![0-9])", Opt);
    static readonly Regex DriverWord = new Regex(@"면허|licen[cs]e", Opt | RegexOptions.IgnoreCase);
    // 카드번호 16자리(또는 아멕스 15자리). 검증 숫자(Luhn)가 맞아야 셉니다.
    static readonly Regex Card = new Regex(@"(?<![0-9])(?:[3-69][0-9]{3}([ -]?)[0-9]{4}\1[0-9]{4}\1[0-9]{4}|3[47][0-9]{2}([ -]?)[0-9]{6}\2[0-9]{5})(?![0-9])", Opt);
    // 휴대폰번호 (010-1234-5678, 01012345678, +82 10-1234-5678)
    static readonly Regex Phone = new Regex(@"(?:(?<![0-9])0|\+82[ -]?)1[016789]([ .-]?)[0-9]{3,4}\1[0-9]{4}(?![0-9])", Opt);
    static readonly Regex Email = new Regex(@"[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}", Opt);
    // 계좌번호는 은행마다 형식이 달라 '계좌', '입금' 같은 말 바로 뒤에 나오는 10~16자리 번호만 셉니다.
    static readonly Regex AccountWord = new Regex(@"계좌|입금|예금주|은행|account", Opt | RegexOptions.IgnoreCase);
    static readonly Regex AccountNumber = new Regex(@"(?<![0-9-])[0-9]{2,6}(?:-[0-9]{2,8}){1,3}(?![0-9-])|(?<![0-9])[0-9]{10,14}(?![0-9])", Opt);

    public static int[] ScanText(string t) {
        int[] c = new int[Kinds];
        foreach (Match m in Rrn.Matches(t)) {
            string digits = m.Groups[1].Value + m.Groups[3].Value;
            bool foreign = digits[6] >= '5';
            if (m.Groups[2].Value.Length == 0 && !RrnChecksum(digits, foreign)) continue;
            c[foreign ? 1 : 0]++;
        }
        foreach (Match m in Passport.Matches(t)) if (Near(t, m.Index, PassportWord)) c[2]++;
        foreach (Match m in Driver.Matches(t)) c[3]++;
        foreach (Match m in DriverPlain.Matches(t)) if (Near(t, m.Index, DriverWord)) c[3]++;
        foreach (Match m in Card.Matches(t)) if (Luhn(m.Value)) c[4]++;
        c[5] = Phone.Matches(t).Count;
        c[6] = Email.Matches(t).Count;
        c[7] = CountAccounts(t);
        return c;
    }

    static int CountAccounts(string t) {
        HashSet<int> found = new HashSet<int>();
        foreach (Match w in AccountWord.Matches(t)) {
            int start = w.Index + w.Length;
            int length = Math.Min(80, t.Length - start);
            if (length <= 0) continue;
            foreach (Match m in AccountNumber.Matches(t.Substring(start, length))) {
                string v = m.Value;
                int digits = 0;
                foreach (char ch in v) if (ch >= '0' && ch <= '9') digits++;
                if (digits < 10 || digits > 16) continue;
                // 휴대폰번호, 주민등록번호, 카드번호, 운전면허번호로 이미 센 것은 빼기
                if (Whole(Phone, v) || Whole(Rrn, v) || Whole(Driver, v) || (Whole(Card, v) && Luhn(v))) continue;
                found.Add(start + m.Index);
            }
        }
        return found.Count;
    }

    static bool Whole(Regex r, string v) {
        Match m = r.Match(v);
        return m.Success && m.Index == 0 && m.Length == v.Length;
    }

    static bool Near(string t, int index, Regex word) {
        int start = Math.Max(0, index - 60);
        int end = Math.Min(t.Length, index + 30);
        return word.IsMatch(t.Substring(start, end - start));
    }

    // 2020년 10월 전에 받은 번호의 검증 숫자 (그 뒤 번호는 '-'가 있으면 그냥 셉니다)
    static bool RrnChecksum(string d, bool foreign) {
        int[] w = { 2, 3, 4, 5, 6, 7, 8, 9, 2, 3, 4, 5 };
        int s = 0;
        for (int i = 0; i < 12; i++) s += (d[i] - '0') * w[i];
        int check = foreign ? (13 - s % 11) % 10 : (11 - s % 11) % 10;
        return check == d[12] - '0';
    }

    static bool Luhn(string v) {
        int sum = 0, n = 0;
        for (int i = v.Length - 1; i >= 0; i--) {
            char ch = v[i];
            if (ch < '0' || ch > '9') continue;
            int d = ch - '0';
            if (n % 2 == 1) { d *= 2; if (d > 9) d -= 9; }
            sum += d;
            n++;
        }
        return n >= 15 && sum % 10 == 0;
    }

    // ---------------------------------------------------------------- 파일 읽기

    public static Result ScanFile(string path) {
        Result r = new Result();
        r.Path = path;
        try {
            FileInfo fi = new FileInfo(path);
            r.Size = fi.Length;
            r.Modified = fi.LastWriteTime;
            if (fi.Length > MaxFileBytes) { r.Note = "너무 큰 파일"; return r; }
            string ext = fi.Extension.ToLowerInvariant();
            string note = null;
            string text;
            if (Array.IndexOf(TextExt, ext) >= 0) text = ReadText(path);
            else if (Array.IndexOf(ZipExt, ext) >= 0) text = ReadZip(path, ext, out note);
            else { r.Note = "검사하지 않는 형식"; return r; }
            if (text == null) { r.Note = note; return r; }
            r.Counts = ScanText(text);
        } catch (FileNotFoundException) {
            r.Note = "파일이 없음";
        } catch (DirectoryNotFoundException) {
            r.Note = "파일이 없음";
        } catch (InvalidDataException) {
            r.Note = "손상된 파일";
        } catch (UnauthorizedAccessException) {
            r.Note = "열 권한 없음";
        } catch (IOException) {
            r.Note = "열 수 없음 (다른 프로그램이 쓰는 중이거나 USB를 뺌)";
        } catch (Exception e) {
            r.Note = "열 수 없음 (" + e.GetType().Name + ")";
        }
        return r;
    }

    static FileStream Open(string path) {
        return new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete, 65536);
    }

    // txt, csv: BOM 이 없으면 UTF-8 인지 확인하고 아니면 한글 윈도우 인코딩(CP949)으로 읽습니다.
    static string ReadText(string path) {
        byte[] bytes;
        using (FileStream fs = Open(path)) {
            int len = (int)Math.Min(fs.Length, (long)MaxChars * 2);
            bytes = new byte[len];
            int read = 0;
            while (read < len) {
                int n = fs.Read(bytes, read, len - read);
                if (n <= 0) break;
                read += n;
            }
            if (read < len) Array.Resize(ref bytes, read);
        }
        if (bytes.Length >= 3 && bytes[0] == 0xEF && bytes[1] == 0xBB && bytes[2] == 0xBF) return Encoding.UTF8.GetString(bytes, 3, bytes.Length - 3);
        if (bytes.Length >= 2 && bytes[0] == 0xFF && bytes[1] == 0xFE) return Encoding.Unicode.GetString(bytes, 2, bytes.Length - 2);
        if (bytes.Length >= 2 && bytes[0] == 0xFE && bytes[1] == 0xFF) return Encoding.BigEndianUnicode.GetString(bytes, 2, bytes.Length - 2);
        try {
            new UTF8Encoding(false, true).GetString(bytes, 0, Math.Max(0, bytes.Length - 4));
            return Encoding.UTF8.GetString(bytes);
        } catch (DecoderFallbackException) {
            return Encoding.GetEncoding(949).GetString(bytes);
        }
    }

    // xlsx, docx, pptx, hwpx 는 zip 안의 XML 에서 글자만 꺼냅니다. 암호를 건 문서는 zip 이 아니라서 열지 못합니다.
    static string ReadZip(string path, string ext, out string note) {
        note = null;
        using (FileStream fs = Open(path)) {
            byte[] sig = new byte[4];
            if (fs.Read(sig, 0, 4) < 4) { note = "빈 파일"; return null; }
            fs.Position = 0;
            if (sig[0] == 0xD0 && sig[1] == 0xCF && sig[2] == 0x11 && sig[3] == 0xE0) { note = "암호 걸린 파일"; return null; }
            if (sig[0] != 0x50 || sig[1] != 0x4B) { note = "읽을 수 없는 형식"; return null; }
            StringBuilder sb = new StringBuilder();
            using (ZipArchive zip = new ZipArchive(fs, ZipArchiveMode.Read)) {
                foreach (ZipArchiveEntry e in zip.Entries) {
                    if (sb.Length >= MaxChars) break;
                    if (!Wanted(ext, e.FullName.Replace('\\', '/'))) continue;
                    using (StreamReader reader = new StreamReader(e.Open(), Encoding.UTF8)) {
                        sb.Append(XmlToText(ReadUpTo(reader, MaxChars - sb.Length))).Append('\n');
                    }
                }
            }
            return sb.ToString();
        }
    }

    static bool Wanted(string ext, string n) {
        if (!n.EndsWith(".xml", StringComparison.OrdinalIgnoreCase)) return false;
        switch (ext) {
            case ".docx":
                return n.StartsWith("word/") && (n.Contains("document") || n.Contains("header") || n.Contains("footer")
                    || n.Contains("footnotes") || n.Contains("endnotes") || n.Contains("comments"));
            case ".xlsx":
            case ".xlsm":
                return n == "xl/sharedStrings.xml" || n.StartsWith("xl/worksheets/");
            case ".pptx":
                return n.StartsWith("ppt/slides/slide") || n.StartsWith("ppt/notesSlides/");
            case ".hwpx":
                return n.StartsWith("Contents/section");
        }
        return false;
    }

    static string ReadUpTo(StreamReader reader, int max) {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[65536];
        int n;
        while (sb.Length < max && (n = reader.Read(buf, 0, Math.Min(buf.Length, max - sb.Length))) > 0) sb.Append(buf, 0, n);
        return sb.ToString();
    }

    // 문단, 표 칸, 엑셀 칸이 끝나는 곳은 줄을 바꾸고 나머지 태그는 지웁니다. (한 문단 안에서 나뉜 글자는 이어 붙임)
    static readonly Regex BreakTags = new Regex(@"</(?:w:p|w:tc|w:tr|a:p|a:tc|hp:p|hp:tc|si|c|row|is)>|<(?:w:br|w:tab|w:cr|a:br|hp:lineBreak|hp:tab)\b[^>]*>", Opt);
    static readonly Regex Tags = new Regex(@"<[^>]*>", Opt);

    static string XmlToText(string xml) {
        return System.Net.WebUtility.HtmlDecode(Tags.Replace(BreakTags.Replace(xml, "\n"), ""));
    }

    // ---------------------------------------------------------------- USB로 복사한 파일 검사 (따로 도는 스레드)

    static readonly ConcurrentQueue<string> requests = new ConcurrentQueue<string>();
    static readonly ConcurrentQueue<Result> results = new ConcurrentQueue<Result>();
    static readonly object gate = new object();
    static Thread worker;

    public static void Enqueue(string path) {
        requests.Enqueue(path);
        lock (gate) {
            if (worker != null && worker.IsAlive) return;
            worker = new Thread(Work);
            worker.IsBackground = true;
            worker.Priority = ThreadPriority.BelowNormal;
            worker.Start();
        }
    }

    static void Work() {
        while (true) {
            string path;
            if (requests.TryDequeue(out path)) results.Enqueue(ScanFile(path));
            else Thread.Sleep(200);
        }
    }

    public static Result[] Drain() {
        List<Result> list = new List<Result>();
        Result r;
        while (results.TryDequeue(out r)) list.Add(r);
        return list.ToArray();
    }

    // ---------------------------------------------------------------- PC 전체 정기 검사 (가장 낮은 우선순위)

    public static long Scanned;
    public static long Skipped;
    public static bool Truncated;
    public static bool Finished;
    public static DateTime StartedAt;
    public static DateTime FinishedAt;
    static List<Result> findings = new List<Result>();
    static Thread fullScan;

    public static bool FullScanRunning { get { return fullScan != null && fullScan.IsAlive; } }

    // 드라이브 바로 아래에서 건너뛰는 폴더 (윈도우, 프로그램)
    static readonly string[] RootSkip = { "windows", "program files", "program files (x86)", "programdata", "$recycle.bin",
        "system volume information", "recovery", "perflogs", "$winreagent", "config.msi", "msocache", "$windows.~bt", "$windows.~ws" };
    // 클라우드에만 있는 파일 (열면 인터넷에서 내려받아지므로 건너뜀)
    const FileAttributes CloudOnly = FileAttributes.Offline | (FileAttributes)0x00040000 | (FileAttributes)0x00400000;

    public static bool StartFullScan(string[] roots) {
        if (FullScanRunning) return false;
        Scanned = 0;
        Skipped = 0;
        Truncated = false;
        Finished = false;
        findings = new List<Result>();
        StartedAt = DateTime.Now;
        fullScan = new Thread(() => {
            try {
                foreach (string root in roots) Walk(root);
            } catch {
            } finally {
                FinishedAt = DateTime.Now;
                Finished = true;
            }
        });
        fullScan.IsBackground = true;
        fullScan.Priority = ThreadPriority.Lowest;
        fullScan.Start();
        return true;
    }

    // 개인정보가 나온 파일 (최대 MaxFindings 개). 가져가면 Finished 가 다시 false 가 됩니다.
    public static Result[] TakeFindings() {
        Finished = false;
        lock (findings) return findings.ToArray();
    }

    static void Walk(string root) {
        Stack<KeyValuePair<string, int>> stack = new Stack<KeyValuePair<string, int>>();
        stack.Push(new KeyValuePair<string, int>(root, 0));
        while (stack.Count > 0) {
            KeyValuePair<string, int> cur = stack.Pop();
            string[] dirs, files;
            try {
                dirs = Directory.GetDirectories(cur.Key);
                files = Directory.GetFiles(cur.Key);
            } catch {
                continue;
            }
            foreach (string d in dirs) {
                try {
                    string name = Path.GetFileName(d).ToLowerInvariant();
                    if (name == "appdata" || name == "node_modules" || name == ".git") continue;
                    if (cur.Value == 0 && Array.IndexOf(RootSkip, name) >= 0) continue;
                    if ((File.GetAttributes(d) & FileAttributes.ReparsePoint) != 0) continue;
                    stack.Push(new KeyValuePair<string, int>(d, cur.Value + 1));
                } catch {
                }
            }
            foreach (string f in files) {
                if (!CanScan(f)) continue;
                try {
                    if ((File.GetAttributes(f) & CloudOnly) != 0) { Interlocked.Increment(ref Skipped); continue; }
                } catch {
                    continue;
                }
                Result r = ScanFile(f);
                Interlocked.Increment(ref Scanned);
                if (r.Note != null) {
                    Interlocked.Increment(ref Skipped);
                } else if (r.Significant) {
                    lock (findings) {
                        if (findings.Count < MaxFindings) findings.Add(r);
                        else Truncated = true;
                    }
                }
                Thread.Sleep(5);   // 사용자 작업을 방해하지 않도록 조금씩 쉽니다
            }
        }
    }
}
'@
}

# 서버 PiCounts 와 같은 순서
$PiKeys = 'rrn', 'foreigner', 'passport', 'driver', 'card', 'phone', 'email', 'account'

function ConvertTo-PiCounts([int[]]$Counts) {
    $o = [ordered]@{}
    for ($i = 0; $i -lt $PiKeys.Count; $i++) { $o[$PiKeys[$i]] = $Counts[$i] }
    $o
}

# 검사할 곳: 모든 고정 디스크 (윈도우·프로그램 폴더와 사용자별 AppData 는 엔진이 건너뜀)
function Get-PiScanRoots {
    @(Get-CimInstance -ClassName Win32_LogicalDisk -Filter 'DriveType=3' | ForEach-Object { "$($_.DeviceID)\" })
}

function Read-PiState {
    try {
        if (Test-Path $PiStatePath) {
            $s = Get-Content -Path $PiStatePath -Raw -Encoding UTF8 | ConvertFrom-Json
            return @{ lastScanAt = [string]$s.lastScanAt; lastRequest = [string]$s.lastRequest }
        }
    } catch { }
    return @{ lastScanAt = ''; lastRequest = '' }
}

function Save-PiState($State) {
    [pscustomobject]$State | ConvertTo-Json | Set-Content -Path $PiStatePath -Encoding UTF8
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

# ---------------------------------------------------------------- 모든 USB 장치 연결 기록

# 장치 관리자 종류 → 기록에 남길 이름. 앞에 있을수록 먼저 적습니다. (키보드로 위장한 USB는 '키보드, USB저장장치'처럼 보입니다)
$UsbKindByClass = [ordered]@{
    'Keyboard' = '키보드'; 'Mouse' = '마우스'; 'DiskDrive' = 'USB저장장치'; 'CDROM' = 'USB저장장치'; 'WPD' = '휴대폰'
    'Net' = '네트워크'; 'Image' = '카메라'; 'Camera' = '카메라'; 'Printer' = '프린터'; 'MEDIA' = '오디오'; 'AudioEndpoint' = '오디오'
    'Bluetooth' = '블루투스'; 'SmartCardReader' = '스마트카드 리더'; 'Biometric' = '지문 인식'; 'Ports' = '시리얼 포트'; 'Modem' = '모뎀'
    'HIDClass' = 'HID 장치'
}

# 지금 USB 포트에 꽂혀 있는 장치 본체 ID
function Get-UsbDeviceIds {
    @([UsbControlDevices]::PresentIdsOnBus('USB') | Where-Object { [UsbControlDevices]::IsUsbDevice($_) })
}

# 새로 꽂힌 USB 장치 하나의 이름, 종류, 포트
function Get-UsbDeviceInfo([string]$InstanceId) {
    $classes = @()
    foreach ($id in @($InstanceId) + @([UsbControlDevices]::Descendants($InstanceId, 4))) {
        $classes += @(Get-NativeProperty $id '{a45c254e-df1c-4efd-8020-67d146a850e0}' 9)   # DEVPKEY_Device_Class
    }
    $kinds = @(@(foreach ($class in $UsbKindByClass.Keys) { if ($classes -contains $class) { $UsbKindByClass[$class] } }) | Select-Object -Unique)
    # 키보드·마우스는 모두 HID 장치이므로 HID 장치는 다른 것이 없을 때만 적습니다.
    if ($kinds.Count -gt 1) { $kinds = @($kinds | Where-Object { $_ -ne 'HID 장치' }) }
    if ($kinds.Count -eq 0) {
        $compatible = Get-NativeProperty $InstanceId '{a45c254e-df1c-4efd-8020-67d146a850e0}' 4   # DEVPKEY_Device_CompatibleIds
        $kinds = if ($compatible -like 'USB\Class_09*') { @('USB 허브') } else { @('기타 USB 장치') }
    }
    $desc = Get-NativeProperty $InstanceId '{540b947e-8b40-45bc-a8a2-6a0b894cbda2}' 4   # DEVPKEY_Device_BusReportedDeviceDesc
    $name = Get-NativeProperty $InstanceId '{a45c254e-df1c-4efd-8020-67d146a850e0}' 2   # DEVPKEY_Device_DeviceDesc
    $info = Get-NativeProperty $InstanceId '{a45c254e-df1c-4efd-8020-67d146a850e0}' 15  # DEVPKEY_Device_LocationInfo
    [pscustomobject]@{
        Kind       = ($kinds -join ', ')
        Name       = [string](@($desc) + @($name) | Select-Object -First 1)
        InstanceId = $InstanceId
        PortLabel  = Format-PortLabel ([string]($info | Select-Object -First 1))
    }
}

function Read-UsbState {
    try {
        if (Test-Path $UsbStatePath) {
            $state = @{}
            # 윈도우 PowerShell 5.1 은 JSON 배열을 통째로 하나로 넘기므로 변수에 담은 뒤 하나씩 꺼냅니다.
            $items = Get-Content -Path $UsbStatePath -Raw -Encoding UTF8 | ConvertFrom-Json
            foreach ($d in $items) {
                if ($d.InstanceId) { $state[[string]$d.InstanceId] = $d }
            }
            return $state
        }
    } catch {
        Write-ErrorLog "저장된 USB 장치 목록을 읽지 못함: $($_.Exception.Message)"
    }
    return $null
}

function Save-UsbState($State) {
    ConvertTo-Json -InputObject @($State.Values) -Depth 3 | Set-Content -Path $UsbStatePath -Encoding UTF8
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
        # 저장 파일 개인정보 검사 주기(일, 0이면 안 함)와 관리자의 [지금 검사] 요청
        piScanDays    = [int]$Raw.piScanDays
        piScanRequest = [string]$Raw.piScanRequest
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

# Body 가 문자열이면 이미 만든 JSON 으로 그대로 보냅니다.
function Invoke-Server($Config, [string]$Path, $Body) {
    $json  = if ($Body -is [string]) { $Body } else { $Body | ConvertTo-Json -Depth 6 -Compress }
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
# PiCounts: 그 파일에서 찾은 개인정보 건수 (ConvertTo-PiCounts), PiNote: 검사하지 못한 이유
function Add-UsbEvent([string]$Action, $Device, [string]$FileName = '', $FileSize = $null, $PiCounts = $null, [string]$PiNote = '') {
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
        piCounts   = $PiCounts
        piNote     = $PiNote
    }
    Add-Content -Path $QueuePath -Value ($item | ConvertTo-Json -Depth 3 -Compress) -Encoding UTF8

    # 이 PC에도 사본을 남깁니다.
    [pscustomobject]@{
        시간   = $now.ToString('yyyy-MM-dd HH:mm:ss')
        동작   = $Action
        종류   = $item.kind
        이름   = if ($item.port) { "$($item.deviceName) [$($item.port)]" }
                 elseif ($FileName) { "$($item.deviceName) → $FileName ($FileSize 바이트)$(if ($PiNote) { " [검사 못 함: $PiNote]" } elseif ($PiCounts) { " [개인정보 " + (($PiCounts.Values | Measure-Object -Sum).Sum) + "건]" })" }
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
