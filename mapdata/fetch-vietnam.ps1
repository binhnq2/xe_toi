# Tải lại / cập nhật bản đồ Việt Nam từ mirror cộng đồng của Organic Maps.
#
# Vì sao không dùng URL trong 12_VIETNAM_MAP_BUILD.md: tới 21/11/2026 host
# download.organicmaps.app và đường dẫn /maps/<version>/ trên cdn.organicmaps.app đều
# không còn trả file. Trang chủ cdn.organicmaps.app chỉ còn danh sách mirror cộng đồng.
#
# Cách dùng:
#   powershell -ExecutionPolicy Bypass -File mapdata/fetch-vietnam.ps1
#   powershell -ExecutionPolicy Bypass -File mapdata/fetch-vietnam.ps1 -Version 261110

[CmdletBinding()]
param(
    [string] $Version = '',
    [string] $Mirror  = 'https://omaps.webfreak.org',
    [string] $OutDir  = ''
)

$ErrorActionPreference = 'Stop'

if (-not $OutDir) { $OutDir = Join-Path $PSScriptRoot 'vietnam-latest' }

function Get-Links([string] $url) {
    $html = (Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 30).Content
    [regex]::Matches($html, 'href="([^"]+)"') | ForEach-Object { $_.Groups[1].Value }
}

if (-not $Version) {
    $versions = Get-Links "$Mirror/maps/" |
        Where-Object { $_ -match '^\./(\d{6})/$' } |
        ForEach-Object { $Matches[1] } |
        Sort-Object
    if (-not $versions) { throw "Không tìm thấy phiên bản nào ở $Mirror/maps/" }
    $Version = $versions[-1]
    Write-Host ("Phiên bản mới nhất trên mirror: {0} (các bản có: {1})" -f $Version, ($versions -join ', '))
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$files = Get-Links "$Mirror/maps/$Version/" | Where-Object { $_ -match 'Vietnam.*\.mwm$' } | ForEach-Object { $_ -replace '^\./','' }
if (-not $files) { throw "Phiên bản $Version không có file Vietnam*.mwm" }

Write-Host ("Tải {0} file vào {1}" -f $files.Count, $OutDir)
foreach ($f in $files) {
    $url = "$Mirror/maps/$Version/$f"
    $dst = Join-Path $OutDir $f
    Write-Host "  $url"
    & curl.exe -L --fail --retry 3 -o $dst $url
    if ($LASTEXITCODE -ne 0) { throw "tải thất bại: $url" }
    $len = (Get-Item $dst).Length
    $sha = (Get-FileHash $dst -Algorithm SHA256).Hash
    "{0}  {1}  {2}" -f $sha, $len, $f | Add-Content -LiteralPath (Join-Path $OutDir 'SHA256SUMS.txt')
    Write-Host ("    ok {0:N0} bytes  {1}" -f $len, $sha)
}

Write-Host ''
Write-Host 'Xong. Chép vào điện thoại:'
Write-Host '  adb push <file>.mwm /sdcard/Android/data/app.organicmaps.ts8/files/'
