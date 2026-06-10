#!/bin/bash
# ============================================================
# Safety NOTE - NAS 자동 APK 배포 스크립트
# 역할: GitHub Release 최신 APK 체크 → 새 버전이면 자동 다운로드 + 서버 등록
# 실행: 크론잡으로 매시간 자동 실행
# ============================================================

GITHUB_REPO="gisubhan-droid/safetynote-android"
NAS_SERVER="http://localhost:3000"
DIST_SECRET="safetynote2026!"
APK_DIR="/volume1/safetynote/dist/apk"
VERSION_FILE="/volume1/safetynote/dist/.last_deployed_version"
LOG_FILE="/var/log/safetynote-auto-deploy.log"
GITHUB_TOKEN=""  # 비공개 저장소면 토큰 필요, 공개면 비워도 됨

# 로그 함수
log() {
  echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" | tee -a "$LOG_FILE"
}

# APK 디렉토리 생성
mkdir -p "$APK_DIR"

log "=== Safety NOTE 자동 배포 체크 시작 ==="

# GitHub API로 최신 Release 정보 조회
if [ -n "$GITHUB_TOKEN" ]; then
  RELEASE_JSON=$(curl -s -H "Authorization: Bearer $GITHUB_TOKEN" \
    "https://api.github.com/repos/${GITHUB_REPO}/releases/latest")
else
  RELEASE_JSON=$(curl -s \
    "https://api.github.com/repos/${GITHUB_REPO}/releases/latest")
fi

# 최신 버전 추출
LATEST_VERSION=$(echo "$RELEASE_JSON" | grep '"tag_name"' | head -1 | sed 's/.*"tag_name": *"v\([^"]*\)".*/\1/')

if [ -z "$LATEST_VERSION" ]; then
  log "❌ GitHub Release 조회 실패 (네트워크 오류 또는 Release 없음)"
  exit 1
fi

log "✅ GitHub 최신 버전: v${LATEST_VERSION}"

# 마지막 배포 버전 확인
if [ -f "$VERSION_FILE" ]; then
  LAST_VERSION=$(cat "$VERSION_FILE")
else
  LAST_VERSION="none"
fi

log "📦 마지막 배포 버전: ${LAST_VERSION}"

# 버전 비교
if [ "$LATEST_VERSION" = "$LAST_VERSION" ]; then
  log "✅ 이미 최신 버전입니다. 배포 불필요."
  exit 0
fi

log "🆕 새 버전 감지: ${LAST_VERSION} → ${LATEST_VERSION}"

# APK 다운로드 URL 추출
APK_URL=$(echo "$RELEASE_JSON" | grep '"browser_download_url"' | grep '\.apk' | head -1 | sed 's/.*"browser_download_url": *"\([^"]*\)".*/\1/')
APK_NAME=$(basename "$APK_URL")

if [ -z "$APK_URL" ]; then
  log "❌ APK 다운로드 URL을 찾을 수 없습니다."
  exit 1
fi

log "⬇️  APK 다운로드 중: $APK_NAME"

# APK 다운로드
DOWNLOAD_PATH="/tmp/${APK_NAME}"
if [ -n "$GITHUB_TOKEN" ]; then
  curl -L -s -H "Authorization: Bearer $GITHUB_TOKEN" \
    "$APK_URL" -o "$DOWNLOAD_PATH"
else
  curl -L -s "$APK_URL" -o "$DOWNLOAD_PATH"
fi

if [ ! -f "$DOWNLOAD_PATH" ] || [ ! -s "$DOWNLOAD_PATH" ]; then
  log "❌ APK 다운로드 실패"
  exit 1
fi

APK_SIZE=$(du -h "$DOWNLOAD_PATH" | cut -f1)
log "✅ 다운로드 완료: $APK_SIZE"

# NAS 서버에 APK 등록
log "📤 NAS 서버에 APK 등록 중..."

RELEASE_NOTE=$(echo "$RELEASE_JSON" | grep '"body"' | head -1 | sed 's/.*"body": *"\([^"]*\)".*/\1/' | cut -c1-200)
[ -z "$RELEASE_NOTE" ] && RELEASE_NOTE="Safety NOTE v${LATEST_VERSION}"

VER_CODE=$(echo "$LATEST_VERSION" | awk -F. '{printf "%d%02d%02d", $1, $2, $3}')

HTTP_CODE=$(curl -s -o /tmp/upload_result.txt -w "%{http_code}" \
  -X POST "${NAS_SERVER}/api/dist/apk/upload" \
  -H "X-Dist-Secret: ${DIST_SECRET}" \
  -F "apk=@${DOWNLOAD_PATH};filename=${APK_NAME}" \
  -F "version=${LATEST_VERSION}" \
  -F "version_code=${VER_CODE}" \
  -F "release_note=${RELEASE_NOTE}" \
  -F "force_update=false" \
  --max-time 120)

UPLOAD_RESULT=$(cat /tmp/upload_result.txt 2>/dev/null)

if [ "$HTTP_CODE" = "200" ]; then
  log "✅ NAS 등록 성공: v${LATEST_VERSION} (${APK_SIZE})"
  log "   응답: ${UPLOAD_RESULT}"
  # 배포 버전 기록
  echo "$LATEST_VERSION" > "$VERSION_FILE"
  # APK 백업 보관
  cp "$DOWNLOAD_PATH" "${APK_DIR}/${APK_NAME}"
  log "✅ APK 백업 저장: ${APK_DIR}/${APK_NAME}"
else
  log "❌ NAS 등록 실패 (HTTP ${HTTP_CODE}): ${UPLOAD_RESULT}"
  exit 1
fi

# 임시 파일 정리
rm -f "$DOWNLOAD_PATH"

log "=== 배포 완료: Safety NOTE v${LATEST_VERSION} ==="
