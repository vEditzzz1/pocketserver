#!/system/bin/sh
# Builds PocketServer on-device. Usage: sh build.sh [gradle task...]  (default :app:assembleDebug)
#
# The packaged gradle launcher has a Termux shebang, so on this device it has to
# be started through the bionic linker + bash (this is what the shell shim does).
set -eu
cd "$(dirname "$0")"

export ANDROID_HOME="${HOME}/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="${HOME}/.gradle"
mkdir -p "$GRADLE_USER_HOME" "$HOME/tmp"
export TMPDIR="$HOME/tmp"
export JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$TMPDIR"

TASK=${1:-:app:assembleDebug}
[ $# -gt 0 ] && shift

exec /system/bin/linker64 "${PREFIX}/bin/bash" "${PREFIX}/bin/gradle" \
    --no-daemon -Dorg.gradle.console=plain -Dorg.gradle.native=false \
    -Duser.home="$HOME" \
    -Pandroid.aapt2FromMavenOverride="${PREFIX}/bin/aapt2" "$TASK" "$@"
