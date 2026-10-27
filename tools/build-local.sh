#!/usr/bin/env bash
# 奶蛙相机 · 本机命令行构建脚本（无需 Android Studio）
#
# 用法：
#   bash tools/build-local.sh :app:assembleDebug
#   bash tools/build-local.sh testDebugUnitTest
#   bash tools/build-local.sh clean
#
# 【换机器时只改下面两行】：指向你自己的 JDK 17 与 Android SDK
# （或在 Android Studio 里直接用 IDE，本脚本不影响 IDE 构建）
set -u

TOOLCHAIN="/c/Users/Abel/WorkBuddy/wk/.build-toolchain"
PROJECT="$(cd "$(dirname "$0")/.." && pwd)"

JAVA_HOME_DIR="$TOOLCHAIN/jdk/jdk-17.0.20.1+1"
ANDROID_SDK_DIR="$TOOLCHAIN/sdk"
GRADLE_BIN="$TOOLCHAIN/gradle/gradle-8.9/bin/gradle.bat"
GRADLE_HOME_DIR="$TOOLCHAIN/gradle-home"

if [ ! -d "$JAVA_HOME_DIR" ] || [ ! -d "$ANDROID_SDK_DIR" ] || [ ! -f "$GRADLE_BIN" ]; then
  echo "工具链缺失：$TOOLCHAIN" >&2
  echo "请改脚本里的 TOOLCHAIN/JAVA_HOME_DIR，或用 Android Studio 打开项目构建。" >&2
  exit 1
fi

export JAVA_HOME
JAVA_HOME="$(cygpath -w "$JAVA_HOME_DIR")"
export ANDROID_HOME
ANDROID_HOME="$(cygpath -w "$ANDROID_SDK_DIR")"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
# 缓存放在工具链目录里，不污染 ~/.gradle
export GRADLE_USER_HOME
GRADLE_USER_HOME="$(cygpath -w "$GRADLE_HOME_DIR")"

echo "JAVA_HOME=$JAVA_HOME"
echo "ANDROID_HOME=$ANDROID_HOME"
exec "$GRADLE_BIN" -p "$(cygpath -w "$PROJECT")" "$@" --no-daemon --console=plain
