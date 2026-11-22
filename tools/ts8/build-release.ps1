# TS8 Lite — one-command release build.
#
# Reproduces the exact artifact the campaign verified, from a clean-ish tree, with the flags this
# host needs. Every flag is explained because two of them (-Pnounity, -Pskiplint) exist only
# because the build host is memory-constrained, not because the product needs them.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File tools/ts8/build-release.ps1
#   powershell -ExecutionPolicy Bypass -File tools/ts8/build-release.ps1 -Tests
#   powershell -ExecutionPolicy Bypass -File tools/ts8/build-release.ps1 -Arm64
#
# Exit codes: 0 = success, 1 = build or test failure.

[CmdletBinding()]
param(
    [switch] $Tests,
    [switch] $Arm64,
    [switch] $Clean,
    [switch] $Unity,
    [string] $JdkHome = '',
    [string] $SdkHome = ''
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$androidDir = Join-Path $repoRoot 'android'
$logDir = Join-Path $repoRoot 'TS8_NOTE\logs'
New-Item -ItemType Directory -Force $logDir | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$log = Join-Path $logDir "build-$stamp.log"

# Resolve the JDK and SDK portably, so this script is not tied to one machine.
#
# Precedence: explicit -JdkHome/-SdkHome > existing JAVA_HOME/ANDROID_HOME > known local layout >
# error with instructions. The reference host keeps its toolchain in D:\Tools\_toolchain, which is
# why that path is a fallback and not a requirement.
function Resolve-Home {
    param(
        [string] $Explicit,
        [string] $EnvName,
        [string[]] $Candidates,
        [string] $Marker,          # a file/dir that must exist inside a valid home
        [string] $What,            # human name, e.g. "JDK 17"
        [string] $ParamName        # the parameter to tell the user to pass, e.g. -JdkHome
    )
    if ($Explicit) {
        if (-not (Test-Path (Join-Path $Explicit $Marker))) {
            throw "$What passed as $ParamName looks wrong (no $Marker inside): $Explicit"
        }
        return $Explicit
    }
    $fromEnv = [Environment]::GetEnvironmentVariable($EnvName)
    if ($fromEnv -and (Test-Path (Join-Path $fromEnv $Marker))) { return $fromEnv }
    foreach ($c in $Candidates) {
        if ($c -and (Test-Path (Join-Path $c $Marker))) { return $c }
    }
    $tried = @($Candidates | Where-Object { $_ } | ForEach-Object { "    $_" }) -join "`n"
    throw "$What not found.`n`n  Looked at: $ParamName, `$env:$EnvName, and:`n$tried`n`n  Pass it explicitly:`n    .\build-release.ps1 $ParamName `"C:\path\to\$What`""
}

$JdkHome = Resolve-Home -Explicit $JdkHome -EnvName 'JAVA_HOME' -Marker 'bin\javac.exe' -What 'JDK 17' `
    -ParamName '-JdkHome' `
    -Candidates @('D:\Tools\_toolchain\jdk-17.0.21+9', "$env:LOCALAPPDATA\Programs\Eclipse Adoptium\jdk-17.0.21+9", 'C:\Program Files\Eclipse Adoptium\jdk-17.0.21+9')

$SdkHome = Resolve-Home -Explicit $SdkHome -EnvName 'ANDROID_HOME' -Marker 'platform-tools' -What 'Android SDK' `
    -ParamName '-SdkHome' `
    -Candidates @('D:\Tools\_toolchain\android-sdk', "$env:LOCALAPPDATA\Android\Sdk", 'C:\Android\Sdk')

# JDK 17 exactly: Gradle 8.14 does not run on JDK 21+.
$env:JAVA_HOME = $JdkHome
$env:ANDROID_HOME = $SdkHome
$env:ANDROID_SDK_ROOT = $SdkHome
# Mockito's ByteBuddy cannot self-attach without this, and 13 tests use Mockito.spy.
$env:JAVA_TOOL_OPTIONS = '-Djdk.attach.allowAttachSelf=true'

Write-Host "  jdk  : $JdkHome"
Write-Host "  sdk  : $SdkHome"

$abi = if ($Arm64) { '-Parm64' } else { '-Parm32' }
$unityFlag = if ($Unity) { @() } else { @('-Pnounity') }

# -Pnounity   disables CMake unity build. Required when free commit memory is under ~4 GB; also why
#             the six IWYU fixes in this campaign exist (unity build hides missing includes).
# -Pskiplint  skips lintVitalAnalyze, which OOMs next to the Gradle daemon on this host. Lint is
#             still runnable explicitly for the accessibility gate.
$args = @(
    '--no-daemon', '--max-workers=1',
    'assembleTs8liteRelease'
) + $(if ($Tests) { @('testTs8liteReleaseUnitTest') } else { @() }) + $unityFlag + @(
    $abi, '-Pnjobs=1', '-Pskiplint'
)
if ($Clean) { $args = @('clean') + $args }

Write-Host "TS8 Lite release build" -ForegroundColor Cyan
Write-Host "  repo : $repoRoot"
Write-Host "  log  : $log"
Write-Host "  abi  : $abi"
Write-Host "  unity: $(if ($Unity) { 'ON' } else { 'OFF (memory-constrained host)' })"
Write-Host ""

Push-Location $androidDir
try
{
    & .\gradlew.bat @args 2>&1 | Tee-Object -FilePath $log | Select-Object -Last 20
    $code = $LASTEXITCODE
}
finally
{
    Pop-Location
}

if ($code -ne 0)
{
    Write-Host "BUILD FAILED (exit $code). Full log: $log" -ForegroundColor Red
    exit 1
}

$apk = Join-Path $androidDir 'app\build\outputs\apk\ts8lite\release\OrganicMaps-25071301-ts8lite-release.apk'
if (-not (Test-Path $apk))
{
    Write-Host "Build reported success but the APK is missing: $apk" -ForegroundColor Red
    exit 1
}

$hash = (Get-FileHash $apk -Algorithm SHA256).Hash
$size = (Get-Item $apk).Length

Write-Host ''
Write-Host "APK  : $apk"
Write-Host ("size : {0:N0} bytes ({1:N2} MB)" -f $size, ($size / 1MB))
Write-Host "sha256: $hash"

# A build that silently loses map assets still "succeeds" here, so the artifact is checked before
# it is called good: see TS8_NOTE/20_LAUNCHER_AUDIT.md and the symlink incident in 21_.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($apk)
try
{
    $required = @('assets/classificator.txt', 'assets/icudt75l.dat', 'assets/types.txt')
    $missing = @()
    foreach ($name in $required)
    {
        $entry = $zip.Entries | Where-Object { $_.FullName -eq $name }
        if (-not $entry -or $entry.Length -le 0) { $missing += $name }
    }
    $so = $zip.Entries | Where-Object { $_.FullName -like '*liborganicmaps.so' }
    if (-not $so) { $missing += 'liborganicmaps.so' }

    if ($missing.Count -gt 0)
    {
        Write-Host ''
        Write-Host "ASSET CHECK FAILED - the APK is missing:" -ForegroundColor Red
        $missing | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
        Write-Host "This usually means the 110 data symlinks point outside the repo tree." -ForegroundColor Yellow
        exit 1
    }
    Write-Host 'asset check: PASS (classificator, icudt, types, liborganicmaps.so present)' -ForegroundColor Green
}
finally
{
    $zip.Dispose()
}

if ($Tests)
{
    Write-Host 'tests: PASS (see the log for per-suite counts)' -ForegroundColor Green
}

Write-Host ''
Write-Host 'Done. Device-only checks remain PENDING PHYSICAL TEST - see TS8_NOTE/25_PHASE9_10_REPORT.md'
