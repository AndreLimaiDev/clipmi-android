# Menjalankan Gradle dengan lingkungan build ClipMI Android (lihat CLAUDE.md).
# Contoh: powershell -ExecutionPolicy Bypass -File scripts\gradle.ps1 assembleDebug
$ErrorActionPreference = 'Stop'

$env:JAVA_HOME = 'D:\dev\jdk-21'
$env:GRADLE_USER_HOME = 'D:\dev\gradle-home'
# TEMP Windows berupa path pendek (BWA-IT~1) yang membuat socket Unix Java gagal; pakai folder ini.
New-Item -ItemType Directory -Force 'D:\dev\tmp' | Out-Null
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=D:/dev/tmp'

$root = Resolve-Path (Join-Path $PSScriptRoot '..')
Push-Location $root
try {
    if (Test-Path (Join-Path $root 'gradlew.bat')) {
        & (Join-Path $root 'gradlew.bat') @args
    } else {
        & 'D:\dev\gradle-9.9.1\bin\gradle.bat' @args
    }
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
