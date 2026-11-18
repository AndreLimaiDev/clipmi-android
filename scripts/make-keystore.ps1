# Membuat kunci tanda tangan APK rilis tim ClipMI (sekali saja), lalu menulis signing.properties untuk Gradle.
#
#   powershell -ExecutionPolicy Bypass -File scripts\make-keystore.ps1
#
# Dijalankan sendiri oleh koordinator tim: kata sandi diketik di jendela ini dan tidak pernah dibagikan.
# Simpan clipmi-release.jks dan kata sandinya di tempat aman (misalnya pengelola kata sandi + salinan offline):
# tanpa kunci yang sama, APK versi berikutnya tidak bisa dipasang sebagai pembaruan di HP anggota tim.
# clipmi-release.jks dan signing.properties sudah di-.gitignore; jangan di-commit.

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$keystore = Join-Path $root 'clipmi-release.jks'
$properties = Join-Path $root 'signing.properties'
$keytool = 'D:\dev\jdk-21\bin\keytool.exe'

if (Test-Path $keystore) {
    Write-Host "Kunci sudah ada: $keystore. Hapus dulu bila memang ingin membuat yang baru (APK lama tidak bisa diperbarui dengan kunci baru)."
    exit 1
}

$alias = 'clipmi'
$name = Read-Host 'Nama tim/organisasi (untuk sertifikat, misalnya "Tim Dakwah")'
$secure = Read-Host 'Kata sandi kunci (minimal 6 karakter)' -AsSecureString
$confirm = Read-Host 'Ulangi kata sandi' -AsSecureString
$plain = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
$plain2 = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($confirm))
if ($plain -ne $plain2 -or $plain.Length -lt 6) {
    Write-Host 'Kata sandi tidak sama atau kurang dari 6 karakter.'
    exit 1
}

& $keytool -genkeypair -v -keystore $keystore -alias $alias -keyalg RSA -keysize 4096 -validity 10000 `
    -dname "CN=ClipMI, O=$name, C=ID" -storepass $plain -keypass $plain
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

@"
storeFile=clipmi-release.jks
storePassword=$plain
keyAlias=$alias
keyPassword=$plain
"@ | Out-File -FilePath $properties -Encoding ascii

Write-Host ''
Write-Host "Selesai. Kunci: $keystore"
Write-Host 'Build APK rilis: bash scripts/gradle.sh :app:assembleRelease'
Write-Host 'Sidik jari sertifikat (simpan untuk verifikasi developer Android):'
& $keytool -list -v -keystore $keystore -alias $alias -storepass $plain | Select-String 'SHA256:'
