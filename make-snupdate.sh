#!/bin/bash
# .snupdate 패키지 생성 스크립트
# NAS에서 실행: bash make-snupdate.sh

VERSION=${1:-"1.0.1"}
OUTPUT="safetynote-v${VERSION}.snupdate"

echo "Safety NOTE v${VERSION} .snupdate 패키지 생성 중..."

# 임시 디렉토리
TMP=$(mktemp -d)

# manifest.json 생성
cat > "$TMP/manifest.json" << EOF
{
  "name": "safetynote",
  "version": "${VERSION}",
  "date": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "description": "Safety NOTE v${VERSION} 업데이트",
  "files": ["app.js", "style.css", "mobile-app.js"]
}
EOF

# 현재 앱 파일 복사
cp /volume1/safetynote/public/static/app.js "$TMP/" 2>/dev/null && echo "✓ app.js 포함"
cp /volume1/safetynote/public/static/style.css "$TMP/" 2>/dev/null && echo "✓ style.css 포함"
cp /volume1/safetynote/public/static/mobile-app.js "$TMP/" 2>/dev/null && echo "✓ mobile-app.js 포함"

# .snupdate = tar.gz 패키지
tar -czf "${OUTPUT}" -C "$TMP" .
rm -rf "$TMP"

echo ""
echo "✅ 패키지 생성 완료: ${OUTPUT} ($(du -h ${OUTPUT} | cut -f1))"
echo "   시스템 설정 > 소프트웨어 업데이트 에서 업로드하세요."
