#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Build and install Android Squeezer APK.

Usage:
  ./build_and_install.sh [--release] [--serial <device_serial>] [--no-build]

Options:
  --release            Build/install release APK (default: debug)
  --serial <serial>    Install to a specific adb device
  --no-build           Skip Gradle build, only install existing APK
  -h, --help           Show this help
EOF
}

BUILD_TYPE="debug"
BUILD_VARIANT="Debug"
DEVICE_SERIAL=""
DO_BUILD=1
PACKAGE_NAME="com.sirius.player.squeezer"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --release)
      BUILD_TYPE="release"
      BUILD_VARIANT="Release"
      shift
      ;;
    --serial)
      if [[ $# -lt 2 ]]; then
        echo "Missing value for --serial" >&2
        usage
        exit 1
      fi
      DEVICE_SERIAL="$2"
      shift 2
      ;;
    --no-build)
      DO_BUILD=0
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage
      exit 1
      ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

GRADLE_TASK=":Squeezer:assemble${BUILD_VARIANT}"
APK_DIR="Squeezer/build/outputs/apk/${BUILD_TYPE}"

if [[ "$DO_BUILD" -eq 1 ]]; then
  echo "Building ${BUILD_TYPE} APK with Gradle task ${GRADLE_TASK}..."
  ./gradlew "$GRADLE_TASK"
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found in PATH. Install Android platform-tools and retry." >&2
  exit 1
fi

APK_PATH="${APK_DIR}/Squeezer-${BUILD_TYPE}.apk"
if [[ ! -f "$APK_PATH" ]]; then
  APK_PATH="$(find "$APK_DIR" -type f -name "*-${BUILD_TYPE}.apk" | head -n 1 || true)"
fi

if [[ -z "$APK_PATH" || ! -f "$APK_PATH" ]]; then
  echo "Could not find APK in ${APK_DIR}." >&2
  echo "Run without --no-build or verify Gradle output paths." >&2
  exit 1
fi

ADB_ARGS=()
if [[ -n "$DEVICE_SERIAL" ]]; then
  ADB_ARGS=(-s "$DEVICE_SERIAL")
fi

adb_cmd() {
  adb "${ADB_ARGS[@]}" "$@"
}

echo "Installing APK: ${APK_PATH}"
INSTALL_OUTPUT=""
set +e
INSTALL_OUTPUT="$(adb_cmd install -r -d "$APK_PATH" 2>&1)"
INSTALL_EXIT=$?
set -e

if [[ $INSTALL_EXIT -ne 0 ]]; then
  echo "$INSTALL_OUTPUT" >&2

  if [[ "$INSTALL_OUTPUT" == *"INSTALL_FAILED_UPDATE_INCOMPATIBLE"* ]]; then
    echo "Detected signature mismatch for ${PACKAGE_NAME}." >&2
    echo "Uninstalling existing package and retrying install..." >&2

    set +e
    UNINSTALL_OUTPUT="$(adb_cmd uninstall "$PACKAGE_NAME" 2>&1)"
    UNINSTALL_EXIT=$?
    set -e

    if [[ $UNINSTALL_EXIT -ne 0 ]]; then
      echo "$UNINSTALL_OUTPUT" >&2
      echo "Failed to uninstall ${PACKAGE_NAME}." >&2
      exit $INSTALL_EXIT
    fi

    adb_cmd install -d "$APK_PATH"
  else
    exit $INSTALL_EXIT
  fi
fi

echo "Install complete."
