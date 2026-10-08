# 설치 파일을 만듭니다. 개발 PC에서 실행합니다.
#   out\UsbControlServer-<버전>.msi               관리 서버 - 윈도우 (자바 실행 환경 포함)
#   out\usb-control-server-<버전>-linux.tar.gz    관리 서버 - 리눅스 (install.sh 가 자바 21을 설치)
#   out\UsbControlAgent-<버전>.msi                직원 PC 감시 프로그램
#
# 필요한 것 (한 번만 설치)
#   - JDK 21 이상 (jlink 포함), Maven
#   - WiX 5:  dotnet tool install --global wix --version 5.0.2
#             wix extension add -g WixToolset.Util.wixext/5.0.2 WixToolset.UI.wixext/5.0.2
#
# 버전: 서버는 server\pom.xml 의 <version>, PC 프로그램은 agent\UsbControl.Common.ps1 의 $AgentVersion.
# 새로 배포할 때마다 버전을 올려야 설치된 PC에서 새 버전으로 바뀝니다.

param(
    [switch]$SkipTests   # 서버 테스트를 건너뜀
)

$ErrorActionPreference = 'Stop'
$root   = Split-Path $PSScriptRoot -Parent
$out    = Join-Path $PSScriptRoot 'out'
$server = Join-Path $root 'server'
$agent  = Join-Path $root 'agent'

function Invoke-Native([string]$What, [scriptblock]$Command) {
    Write-Host "== $What" -ForegroundColor Cyan
    & $Command
    if ($LASTEXITCODE -ne 0) { throw "$What 실패 (종료 코드 $LASTEXITCODE)" }
}

function Find-JdkTool([string]$Name) {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\$Name.exe"))) { return Join-Path $env:JAVA_HOME "bin\$Name.exe" }
    $cmd = Get-Command "$Name.exe" -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw "$Name 을 찾을 수 없습니다. JDK 21 이상을 설치하고 JAVA_HOME 을 설정하세요."
}

$serverVersion = ([xml](Get-Content (Join-Path $server 'pom.xml') -Raw -Encoding UTF8)).project.version
$agentVersion  = if ((Get-Content (Join-Path $agent 'UsbControl.Common.ps1') -Raw -Encoding UTF8) -match "\`$AgentVersion\s*=\s*'([\d.]+)'") { $Matches[1] } else { throw 'PC 프로그램 버전을 찾지 못했습니다.' }
Write-Host "서버 $serverVersion, PC 프로그램 $agentVersion"

if (Test-Path $out) { Remove-Item -Path $out -Recurse -Force }
New-Item -ItemType Directory -Path $out | Out-Null

# 1. 서버 실행 파일
Push-Location $server
try {
    $mvnArgs = @('-q', 'package')
    if ($SkipTests) { $mvnArgs += '-DskipTests' }
    Invoke-Native '서버 빌드 (mvn package)' { mvn @mvnArgs }
} finally {
    Pop-Location
}
$jar = Join-Path $server 'target\usb-control-server.jar'

# 2. 서버에 같이 넣을 자바 실행 환경 (필요한 부분만)
$runtime = Join-Path $out 'runtime'
$jlink = Find-JdkTool 'jlink'
Invoke-Native '자바 실행 환경 만들기 (jlink)' {
    & $jlink --add-modules 'java.se,jdk.unsupported,jdk.zipfs,jdk.charsets,jdk.localedata,jdk.management,jdk.crypto.cryptoki' `
        --include-locales 'en,ko' --strip-debug --no-header-files --no-man-pages --compress zip-6 --output $runtime
}

# 3. 설치 파일
$wixArgs = @('-arch', 'x64', '-culture', 'ko-KR', '-ext', 'WixToolset.Util.wixext', '-ext', 'WixToolset.UI.wixext')
Invoke-Native '관리 서버 MSI' {
    wix build @wixArgs (Join-Path $PSScriptRoot 'server.wxs') -d "Version=$serverVersion" -d "JarPath=$jar" `
        -d "ScriptDir=$(Join-Path $server 'windows')" -d "RuntimeDir=$runtime" -o (Join-Path $out "UsbControlServer-$serverVersion.msi")
}
Invoke-Native 'PC 프로그램 MSI' {
    wix build @wixArgs (Join-Path $PSScriptRoot 'agent.wxs') -d "Version=$agentVersion" -d "AgentDir=$agent" `
        -o (Join-Path $out "UsbControlAgent-$agentVersion.msi")
}

# 4. 리눅스용 관리 서버 묶음
# 윈도우 tar 는 리눅스 권한을 모르므로 mtree 목록으로 권한(스크립트 755, 나머지 644)과 소유자(root)를 정해 줍니다.
function ConvertTo-MtreePath([string]$Path) {
    $sb = New-Object System.Text.StringBuilder
    foreach ($b in [Text.Encoding]::UTF8.GetBytes(($Path -replace '\\', '/'))) {
        if ($b -le 32 -or $b -ge 127 -or $b -eq 92 -or $b -eq 35) { [void]$sb.Append('\' + [Convert]::ToString($b, 8).PadLeft(3, '0')) }
        else { [void]$sb.Append([char]$b) }
    }
    $sb.ToString()
}
$linuxName = "usb-control-server-$serverVersion-linux"
$mtree = @('#mtree', "$linuxName type=dir mode=0755 uid=0 gid=0 uname=root gname=root")
foreach ($f in @(@($jar, '0644'), @((Join-Path $server 'linux\install.sh'), '0755'), @((Join-Path $server 'linux\uninstall.sh'), '0755'))) {
    $mtree += "$linuxName/$(Split-Path $f[0] -Leaf) type=file mode=$($f[1]) uid=0 gid=0 uname=root gname=root contents=$(ConvertTo-MtreePath $f[0])"
}
$mtreeFile = Join-Path $out 'linux.mtree'
[IO.File]::WriteAllText($mtreeFile, ($mtree -join "`n") + "`n", (New-Object System.Text.UTF8Encoding $false))
Invoke-Native '리눅스 묶음 (tar.gz)' { tar -czf (Join-Path $out "$linuxName.tar.gz") "@$mtreeFile" }

Remove-Item -Path $runtime -Recurse -Force
Remove-Item -Path $mtreeFile
Get-ChildItem -Path $out -Filter *.wixpdb | Remove-Item
Write-Host ''
Write-Host '완료:' -ForegroundColor Green
Get-ChildItem -Path $out -File | Where-Object { $_.Extension -in '.msi', '.gz' } | ForEach-Object { Write-Host ("  {0}  ({1:N1} MB)" -f $_.FullName, ($_.Length / 1MB)) }
