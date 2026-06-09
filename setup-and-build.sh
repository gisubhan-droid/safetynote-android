#!/bin/bash
# Safety NOTE Android APK 자동 빌드 스크립트
# 사용법: bash setup-and-build.sh
# 필요조건: Node.js 18+, JDK 17+, Android SDK (ANDROID_HOME 설정)

set -e

echo "========================================"
echo "  Safety NOTE Android APK 빌드 시작"
echo "========================================"

# 환경 확인
if [ -z "$ANDROID_HOME" ]; then
  echo "❌ ANDROID_HOME 환경변수가 설정되지 않았습니다."
  echo "   예: export ANDROID_HOME=~/Android/Sdk"
  exit 1
fi

if ! command -v java &> /dev/null; then
  echo "❌ Java가 설치되지 않았습니다. JDK 17+ 설치 필요"
  exit 1
fi

if ! command -v node &> /dev/null; then
  echo "❌ Node.js가 설치되지 않았습니다."
  exit 1
fi

echo "✓ Java: $(java -version 2>&1 | head -1)"
echo "✓ Node: $(node --version)"
echo "✓ ANDROID_HOME: $ANDROID_HOME"
echo ""

# npm install
echo "[1/4] 패키지 설치 중..."
npm install

# Android 플랫폼 추가 (없을 경우만)
if [ ! -d "android" ]; then
  echo "[2/4] Android 플랫폼 추가 중..."
  npx cap add android
else
  echo "[2/4] Android 플랫폼 이미 존재, 동기화만 수행"
fi

# 웹 에셋 동기화
echo "[3/4] 웹 에셋 동기화 중..."
npx cap sync android

# APK 빌드
echo "[4/4] APK 빌드 중 (시간이 걸릴 수 있습니다)..."
cd android
chmod +x gradlew
./gradlew assembleDebug

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK_PATH" ]; then
  SIZE=$(du -h "$APK_PATH" | cut -f1)
  echo ""
  echo "========================================"
  echo "  ✅ APK 빌드 완료!"
  echo "  파일: android/$APK_PATH"
  echo "  크기: $SIZE"
  echo "========================================"
else
  echo "❌ APK 파일을 찾을 수 없습니다."
  exit 1
fi
