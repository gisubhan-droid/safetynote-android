#!/bin/bash
# =====================================================
# Safety NOTE APK Release Keystore 생성 스크립트
# 로컬 PC에서 딱 한 번만 실행하세요!
# Java(keytool)가 설치된 환경에서 실행
# =====================================================

set -e

KEYSTORE_FILE="safetynote-release.keystore"
KEY_ALIAS="safetynote"
VALIDITY_DAYS=36500   # 100년

echo "================================================"
echo "  Safety NOTE Release Keystore 생성"
echo "================================================"
echo ""
echo "⚠️  경고: 이 keystore를 잃어버리면 앱 업데이트 불가!"
echo "    → 생성 후 안전한 곳에 백업하세요 (NAS, USB 등)"
echo ""

# 비밀번호 입력
read -s -p "🔑 Keystore 비밀번호 입력 (기억하기 쉬운 것, 8자 이상): " KS_PASS
echo ""
read -s -p "🔑 Keystore 비밀번호 확인: " KS_PASS2
echo ""

if [ "$KS_PASS" != "$KS_PASS2" ]; then
    echo "❌ 비밀번호가 일치하지 않습니다."
    exit 1
fi

KEY_PASS="$KS_PASS"   # Key 비밀번호 = Keystore 비밀번호 (동일하게 설정)

echo ""
echo "📋 개발자 정보 입력 (영문 권장, 엔터로 기본값 사용 가능):"
read -p "이름 (예: SafetyNOTE Dev) [SafetyNOTE Dev]: " DNAME_CN
DNAME_CN="${DNAME_CN:-SafetyNOTE Dev}"

read -p "조직 (예: LinkMax) [LinkMax]: " DNAME_O
DNAME_O="${DNAME_O:-LinkMax}"

read -p "국가 코드 (예: KR) [KR]: " DNAME_C
DNAME_C="${DNAME_C:-KR}"

DNAME="CN=${DNAME_CN}, O=${DNAME_O}, C=${DNAME_C}"

echo ""
echo "🔄 Keystore 생성 중..."

keytool -genkeypair \
  -v \
  -keystore "$KEYSTORE_FILE" \
  -alias "$KEY_ALIAS" \
  -keyalg RSA \
  -keysize 2048 \
  -validity $VALIDITY_DAYS \
  -storepass "$KS_PASS" \
  -keypass "$KEY_PASS" \
  -dname "$DNAME"

if [ $? -ne 0 ]; then
    echo "❌ Keystore 생성 실패"
    exit 1
fi

echo ""
echo "✅ Keystore 생성 완료: $KEYSTORE_FILE"
echo ""

# Base64 인코딩 (GitHub Secrets에 등록할 값)
echo "================================================"
echo "  GitHub Secrets에 등록할 값들"
echo "================================================"
echo ""
echo "▼ 아래 명령어를 실행해서 나오는 값을 복사하세요:"
echo ""
echo "  KEYSTORE_BASE64 값:"
BASE64_VAL=$(base64 -w 0 "$KEYSTORE_FILE")
echo "$BASE64_VAL"
echo ""
echo "─────────────────────────────────────────────"
echo "  KEYSTORE_PASSWORD: $KS_PASS"
echo "  KEY_ALIAS:         $KEY_ALIAS"
echo "  KEY_PASSWORD:      $KEY_PASS"
echo "─────────────────────────────────────────────"
echo ""
echo "📌 GitHub Secrets 등록 방법:"
echo "   1. GitHub 저장소 → Settings → Secrets and variables → Actions"
echo "   2. 'New repository secret' 클릭"
echo "   3. 위 4개 값을 각각 등록"
echo ""
echo "⚠️  $KEYSTORE_FILE 파일을 안전한 곳에 백업하세요!"
echo "   (git에는 절대 올리지 마세요)"
