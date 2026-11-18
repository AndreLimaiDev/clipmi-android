# Membuat APK rilis ClipMI Android untuk diunggah ke GitHub (repo rilis Android, lihat clipmi.updateRepo).
#
#   powershell -ExecutionPolicy Bypass -File scripts\release.ps1 -Version 0.1.0
#
# Langkah:
#   1. Menaikkan clipmi.versionName (ke -Version) dan clipmi.versionCode (+1) di gradle.properties.
#   2. Membangun APK rilis bertanda tangan kunci tim (signing.properties wajib ada; buat sekali dengan
#      scripts\make-keystore.ps1). native/out harus sudah ada (native/build-native.sh).
#   3. Menyalin APK ke dist\ClipMI-<versi>.apk, menghitung SHA-256, dan menulis draf catatan rilis
#      dist\ClipMI-<versi>.md (SHA-256 di baris terakhir; aplikasi mencocokkannya sebelum memasang).
#
# Setelah itu (manual, oleh koordinator): buat rilis baru di GitHub dengan tag v<versi>, tempel isi catatan rilis,
# lampirkan APK, lalu terbitkan. Setelah terbit, cocokkan SHA-256 APK yang terunggah dengan dist\ClipMI-<versi>.md.

param(
    [Parameter(Mandatory = $true)][string]$Version
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$props = Join-Path $root 'gradle.properties'

if ($Version -notmatch '^\d+\.\d+\.\d+$') { Write-Host 'Versi harus berbentuk 1.2.3.'; exit 1 }
if (-not (Test-Path (Join-Path $root 'signing.properties'))) {
    Write-Host 'signing.properties belum ada. Jalankan scripts\make-keystore.ps1 dulu (sekali saja).'
    exit 1
}
if (-not (Test-Path (Join-Path $root 'native\out\arm64-v8a'))) {
    Write-Host 'native\out belum ada. Bangun library native dulu (native/build-native.sh).'
    exit 1
}

$text = Get-Content $props -Raw
$current = [regex]::Match($text, '(?m)^clipmi\.versionName=(.+)$').Groups[1].Value.Trim()
$code = [int][regex]::Match($text, '(?m)^clipmi\.versionCode=(\d+)$').Groups[1].Value
if ([version]$Version -le [version]$current) {
    Write-Host "Versi baru ($Version) harus lebih besar dari versi sekarang ($current)."
    exit 1
}
$newCode = $code + 1
$text = [regex]::Replace($text, '(?m)^clipmi\.versionName=.+$', "clipmi.versionName=$Version")
$text = [regex]::Replace($text, '(?m)^clipmi\.versionCode=\d+$', "clipmi.versionCode=$newCode")
[IO.File]::WriteAllText($props, $text)
Write-Host "Versi $current ($code) -> $Version ($newCode)"

& (Join-Path $PSScriptRoot 'gradle.ps1') ':app:assembleRelease'
if ($LASTEXITCODE -ne 0) { Write-Host 'Build rilis gagal.'; exit 1 }

$apk = Join-Path $root 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path $apk)) { Write-Host "APK tidak ditemukan: $apk (apakah ditandatangani?)"; exit 1 }
$dist = Join-Path $root 'dist'
New-Item -ItemType Directory -Force $dist | Out-Null
$out = Join-Path $dist "ClipMI-$Version.apk"
Copy-Item $apk $out -Force
$sha = (Get-FileHash $out -Algorithm SHA256).Hash.ToLower()
$size = [math]::Round((Get-Item $out).Length / 1MB, 1)

$notes = @"
## Yang baru

- (tulis perubahan untuk anggota tim di sini)

SHA-256: $sha
"@
[IO.File]::WriteAllText((Join-Path $dist "ClipMI-$Version.md"), $notes)

Write-Host ''
Write-Host "APK      : $out ($size MB)"
Write-Host "SHA-256  : $sha"
Write-Host "Catatan  : $(Join-Path $dist "ClipMI-$Version.md")"
Write-Host "Tag rilis: v$Version"
