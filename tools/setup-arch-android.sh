#!/usr/bin/env bash
set -euo pipefail

if [[ "${EUID}" -eq 0 ]]; then
    echo "Run this script as your normal user; it uses sudo for pacman." >&2
    exit 1
fi

ANDROID_HOME="${ANDROID_HOME:-${HOME}/Android/Sdk}"
CMDLINE_TOOLS_VERSION="13114758"
CMDLINE_TOOLS_DIR="${ANDROID_HOME}/cmdline-tools/latest"
SDKMANAGER="${CMDLINE_TOOLS_DIR}/bin/sdkmanager"
JAVA_HOME="/usr/lib/jvm/java-17-openjdk"

sudo pacman -Syu --needed base-devel git curl unzip cmake jdk17-openjdk

if [[ ! -x "${SDKMANAGER}" ]]; then
    archive="${TMPDIR:-/tmp}/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}.zip"
    mkdir -p "${ANDROID_HOME}/cmdline-tools"
    curl -fL "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" -o "${archive}"
    rm -rf "${ANDROID_HOME}/cmdline-tools/extracted" "${CMDLINE_TOOLS_DIR}"
    mkdir -p "${ANDROID_HOME}/cmdline-tools/extracted"
    unzip -q "${archive}" -d "${ANDROID_HOME}/cmdline-tools/extracted"
    mv "${ANDROID_HOME}/cmdline-tools/extracted/cmdline-tools" "${CMDLINE_TOOLS_DIR}"
    rm -rf "${ANDROID_HOME}/cmdline-tools/extracted"
fi

export JAVA_HOME ANDROID_HOME
export PATH="${JAVA_HOME}/bin:${CMDLINE_TOOLS_DIR}/bin:${ANDROID_HOME}/platform-tools:${PATH}"

yes | "${SDKMANAGER}" --sdk_root="${ANDROID_HOME}" --licenses >/dev/null
"${SDKMANAGER}" --sdk_root="${ANDROID_HOME}" \
    "platform-tools" \
    "platforms;android-36" \
    "build-tools;35.0.0" \
    "ndk;27.0.12077973" \
    "cmake;3.22.1"

for shell_rc in "${HOME}/.bashrc" "${HOME}/.zshrc"; do
    touch "${shell_rc}"
    grep -qxF 'export JAVA_HOME=/usr/lib/jvm/java-17-openjdk' "${shell_rc}" ||
        printf '\nexport JAVA_HOME=/usr/lib/jvm/java-17-openjdk\n' >> "${shell_rc}"
    grep -qxF "export ANDROID_HOME=${ANDROID_HOME}" "${shell_rc}" ||
        printf 'export ANDROID_HOME=%s\n' "${ANDROID_HOME}" >> "${shell_rc}"
    grep -qxF 'export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"' "${shell_rc}" ||
        printf 'export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"\n' >> "${shell_rc}"
done

echo
echo "Android build dependencies installed. Open a new shell, then run:"
echo "  ./gradlew :app:testDebugUnitTest"
echo "  ./gradlew assembleDebug"