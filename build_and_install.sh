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

Environment:
  JAVA_HOME            Optional JDK to use for Gradle builds (must contain bin/javac)
  ADB_BIN              Optional adb binary to use for install
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

read_local_sdk_dir() {
  local sdk_dir=""

  if [[ -f "${SCRIPT_DIR}/local.properties" ]]; then
    sdk_dir="$(sed -n 's/^sdk\.dir=//p' "${SCRIPT_DIR}/local.properties" | head -n 1)"
    sdk_dir="${sdk_dir//\\:/:}"
    sdk_dir="${sdk_dir//\\\\/\\}"
  fi

  if [[ -n "$sdk_dir" ]]; then
    printf '%s\n' "$sdk_dir"
  fi
}

resolve_android_sdk() {
  local candidate=""
  local -a sdk_candidates=()

  if [[ -n "${ANDROID_SDK_ROOT:-}" && -d "${ANDROID_SDK_ROOT}" ]]; then
    printf '%s\n' "$ANDROID_SDK_ROOT"
    return 0
  fi

  if [[ -n "${ANDROID_HOME:-}" && -d "${ANDROID_HOME}" ]]; then
    printf '%s\n' "$ANDROID_HOME"
    return 0
  fi

  candidate="$(read_local_sdk_dir || true)"
  if [[ -n "$candidate" && -d "$candidate" ]]; then
    printf '%s\n' "$candidate"
    return 0
  fi

  for candidate in \
    "$HOME/Android/Sdk" \
    "$HOME/Android/sdk" \
    "/opt/android-sdk" \
    "/usr/lib/android-sdk"
  do
    if [[ -d "$candidate" ]]; then
      sdk_candidates+=("$candidate")
    fi
  done

  if [[ ${#sdk_candidates[@]} -gt 0 ]]; then
    printf '%s\n' "${sdk_candidates[0]}"
    return 0
  fi

  return 1
}

resolve_adb() {
  local candidate=""
  local sdk_dir=""
  local -a adb_candidates=()

  if [[ -n "${ADB_BIN:-}" && -x "${ADB_BIN}" ]]; then
    printf '%s\n' "$ADB_BIN"
    return 0
  fi

  if command -v adb >/dev/null 2>&1; then
    command -v adb
    return 0
  fi

  sdk_dir="$(resolve_android_sdk || true)"
  if [[ -n "$sdk_dir" ]]; then
    adb_candidates+=("${sdk_dir}/platform-tools/adb")
  fi

  candidate="$(read_local_sdk_dir || true)"
  if [[ -n "$candidate" ]]; then
    adb_candidates+=("${candidate}/platform-tools/adb")
  fi

  for sdk_dir in \
    "${ANDROID_SDK_ROOT:-}" \
    "${ANDROID_HOME:-}" \
    "$HOME/Android/Sdk" \
    "$HOME/Android/sdk" \
    "/opt/android-sdk" \
    "/usr/lib/android-sdk"
  do
    if [[ -n "$sdk_dir" ]]; then
      adb_candidates+=("${sdk_dir}/platform-tools/adb")
    fi
  done

  for candidate in "${adb_candidates[@]}"; do
    if [[ -x "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done

  return 1
}

resolve_java_home() {
  local candidate=""
  local -a java_homes=()

  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/javac" ]]; then
    printf '%s\n' "$JAVA_HOME"
    return 0
  fi

  if command -v javac >/dev/null 2>&1; then
    candidate="$(readlink -f "$(command -v javac)")"
    candidate="$(dirname "$(dirname "$candidate")")"
    if [[ -x "$candidate/bin/javac" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  fi

  for candidate in \
    "/usr/lib/jvm/default-java" \
    "/usr/lib/jvm/java-21-openjdk-amd64" \
    "/usr/lib/jvm/java-17-openjdk-amd64" \
    "/usr/lib/jvm/java-11-openjdk-amd64" \
    "$HOME/android-studio/jbr" \
    "$HOME/Android/android-studio/jbr" \
    "/opt/android-studio/jbr" \
    "/snap/android-studio/current/android-studio/jbr"
  do
    if [[ -n "$candidate" && -x "$candidate/bin/javac" ]]; then
      java_homes+=("$candidate")
    fi
  done

  if [[ ${#java_homes[@]} -gt 0 ]]; then
    printf '%s\n' "${java_homes[0]}"
    return 0
  fi

  return 1
}

GRADLE_TASK=":Squeezer:assemble${BUILD_VARIANT}"
APK_DIR="Squeezer/build/outputs/apk/${BUILD_TYPE}"

ANDROID_SDK_ROOT="$(resolve_android_sdk || true)"
if [[ -n "$ANDROID_SDK_ROOT" ]]; then
  export ANDROID_SDK_ROOT
  export ANDROID_HOME="$ANDROID_SDK_ROOT"
fi

if [[ "$DO_BUILD" -eq 1 ]]; then
  JAVA_HOME="$(resolve_java_home || true)"
  if [[ -z "$JAVA_HOME" ]]; then
    echo "Could not find a JDK with javac for Gradle." >&2
    echo "Install a full JDK (for example OpenJDK 17 or 21) or set JAVA_HOME to a JDK path." >&2
    exit 1
  fi

  if [[ -z "$ANDROID_SDK_ROOT" ]]; then
    echo "Could not find Android SDK." >&2
    echo "Set ANDROID_SDK_ROOT or ANDROID_HOME, or create local.properties with sdk.dir=/path/to/sdk." >&2
    exit 1
  fi

  export JAVA_HOME
  export PATH="$JAVA_HOME/bin:$PATH"

  echo "Building ${BUILD_TYPE} APK with Gradle task ${GRADLE_TASK}..."
  ./gradlew "$GRADLE_TASK"
fi

ADB_BIN="$(resolve_adb || true)"
if [[ -z "$ADB_BIN" ]]; then
  echo "Could not find adb. Set ADB_BIN, ANDROID_SDK_ROOT, or ANDROID_HOME, or install Android platform-tools." >&2
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
  "$ADB_BIN" "${ADB_ARGS[@]}" "$@"
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
