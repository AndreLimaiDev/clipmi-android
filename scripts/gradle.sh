#!/usr/bin/env bash
# Menjalankan Gradle dengan lingkungan build ClipMI Android (lihat CLAUDE.md).
# Contoh: scripts/gradle.sh assembleDebug
set -euo pipefail

export JAVA_HOME="D:/dev/jdk-21"
export GRADLE_USER_HOME="D:/dev/gradle-home"
# TEMP Windows berupa path pendek (BWA-IT~1) yang membuat socket Unix Java gagal; pakai folder ini.
mkdir -p /d/dev/tmp
export JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=D:/dev/tmp"

cd "$(dirname "$0")/.."

if [ -f ./gradlew ]; then
    exec ./gradlew "$@"
else
    exec /d/dev/gradle-9.9.1/bin/gradle "$@"
fi
