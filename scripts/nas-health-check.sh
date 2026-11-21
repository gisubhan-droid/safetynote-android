#!/bin/bash
# ============================================================
# Safety NOTE - NAS 재부팅 후 서비스 자동 기동 점검 스크립트 (PM2 기준)
#
# 사용법
#   bash nas-health-check.sh              # 1회 점검
#   bash nas-health-check.sh --wait 300   # 부팅 직후용: 서버가 뜰 때까지 최대 300초 대기 후 점검
#   bash nas-health-check.sh --quiet      # 결과 한 줄만 출력 (작업 스케줄러용, 전체는 로그 파일에 기록)
#
# 종료 코드: 0 = 정상 / 1 = 정상이지만 경고 있음 / 2 = 장애
#
# ※ 읽기 전용 점검입니다. 서버를 재시작하거나 설정을 바꾸지 않습니다.
#
# 주의: 이 서버는 존재하지 않는 경로도 앱 화면(HTML)과 함께 HTTP 200 으로 응답합니다.
#       그래서 상태코드만 보면 안 되고, JSON 응답(git_hash 포함)인지까지 확인합니다.
#
# 환경변수(모두 선택)
#   SN_ROOT         서버 폴더 (기본: 자동 감지 /volumeN/safetynote)
#   SN_HTTPS_URL    서버 주소 (기본 https://127.0.0.1:3443)
#   SN_HTTP_URL     FCM 내부 HTTP 포트 (기본 http://127.0.0.1:3444, 실패 시 경고만)
#   SN_PUBLIC_URL   외부 주소 점검 (기본 https://linkmax.myds.me:3443, 실패 시 경고만. 끄려면 "none")
#   SN_HEALTH_PATH  JSON 점검 경로 (기본 /api/diagnostics/server-version)
#   SN_PM2_APP      PM2 앱 이름 (기본 safetynote)
#   SN_PM2_BIN      pm2 실행 파일 경로 (기본 자동 탐색)
#   SN_LOG          로그 파일 (기본 /var/log/safetynote-healthcheck.log, 불가 시 /tmp)
# ============================================================

export HOME="${HOME:-/root}"

# --- 볼륨/경로 자동 감지 ---
ROOT="${SN_ROOT:-}"
if [ -z "$ROOT" ]; then
  for v in volume1 volume2 volume3 volume4; do
    [ -d "/$v/safetynote" ] && ROOT="/$v/safetynote" && break
  done
  [ -z "$ROOT" ] && ROOT="/volume1/safetynote"
fi
VOLDIR="$(dirname "$ROOT")"

HTTPS_URL="${SN_HTTPS_URL:-https://127.0.0.1:3443}"
HTTP_URL="${SN_HTTP_URL:-http://127.0.0.1:3444}"
PUBLIC_URL="${SN_PUBLIC_URL:-https://linkmax.myds.me:3443}"
HEALTH_PATH="${SN_HEALTH_PATH:-/api/diagnostics/server-version}"
PM2_APP="${SN_PM2_APP:-safetynote}"
LOG_FILE="${SN_LOG:-/var/log/safetynote-healthcheck.log}"
DATA_DIRS="${SN_DATA_DIRS:-$VOLDIR/safetynote $VOLDIR/safetynote_data}"
SNAPSHOT_DIRS="${SN_SNAPSHOT_DIRS:-$VOLDIR/safetynote/#snapshot $VOLDIR/safetynote_data/#snapshot}"
CURL_TIMEOUT=8

# DSM 작업 스케줄러는 PATH 가 짧으므로 Node/PM2 경로를 직접 추가
for n in Node.js_v18 Node.js_v20; do
  [ -d "$VOLDIR/@appstore/$n/usr/local/bin" ] && PATH="$VOLDIR/@appstore/$n/usr/local/bin:$PATH"
done
export PATH="/usr/local/bin:/usr/bin:/bin:$PATH"

WAIT_SEC=0; QUIET=0
while [ $# -gt 0 ]; do
  case "$1" in
    --wait)  WAIT_SEC="${2:-300}"; shift 2 ;;
    --quiet) QUIET=1; shift ;;
    -h|--help) sed -n '2,28p' "$0"; exit 0 ;;
    *) echo "알 수 없는 옵션: $1 (--help 참고)"; exit 64 ;;
  esac
done

if ! touch "$LOG_FILE" 2>/dev/null; then
  LOG_FILE="/tmp/safetynote-healthcheck.log"
  touch "$LOG_FILE" 2>/dev/null || LOG_FILE="/dev/null"
fi

FAILS=0; WARNS=0; SUMMARY=""
out()  { echo "$1" >> "$LOG_FILE"; [ "$QUIET" -eq 0 ] && echo "$1"; return 0; }
ts()   { date '+%Y-%m-%d %H:%M:%S'; }
ok()   { out "  ✅ $1"; }
warn() { out "  ⚠️  $1"; WARNS=$((WARNS+1)); SUMMARY="${SUMMARY}  ⚠️  $1\n"; }
fail() { out "  ❌ $1"; FAILS=$((FAILS+1)); SUMMARY="${SUMMARY}  ❌ $1\n"; }
info() { out "     $1"; }
head1(){ out ""; out "[$1]"; }

# JSON 응답 점검: 성공이면 0 + 전역 HC_BODY 에 본문 저장
#  - HTTP 2xx 이고 본문에 "git_hash" 가 있어야 진짜 서버 응답으로 인정
#  - HTML(앱 화면 폴백)이면 실패 처리
HC_CODE=""; HC_BODY=""
probe_json() {
  local url="$1" tmp
  tmp=$(mktemp 2>/dev/null || echo /tmp/.snhc.$$)
  HC_CODE=$(curl -k -s -m "$CURL_TIMEOUT" -o "$tmp" -w '%{http_code}' "$url" 2>/dev/null)
  [ -z "$HC_CODE" ] && HC_CODE="000"
  HC_BODY=$(head -c 2000 "$tmp" 2>/dev/null)
  rm -f "$tmp"
  [[ "$HC_CODE" =~ ^2[0-9][0-9]$ ]] && echo "$HC_BODY" | grep -q '"git_hash"'
}
probe_why() {   # 실패 원인 설명
  if [ "$HC_CODE" = "000" ]; then echo "연결 불가(서버 꺼짐/포트 닫힘/방화벽)"
  elif [[ "$HC_CODE" =~ ^2 ]] && echo "$HC_BODY" | grep -qi '<html'; then echo "HTTP $HC_CODE 이지만 JSON 이 아닌 앱 화면(HTML) 응답 — API 가 정상 기동하지 않았을 가능성"
  elif [[ "$HC_CODE" =~ ^2 ]]; then echo "HTTP $HC_CODE 이지만 예상한 JSON(git_hash) 이 아님"
  else echo "HTTP $HC_CODE"; fi
}

json_field() { echo "$HC_BODY" | sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p" | head -1; }

out "============================================================"
out "Safety NOTE 서비스 점검 시작: $(ts)  (host: $(hostname))"
out "서버 폴더: $ROOT"
out "============================================================"

command -v curl >/dev/null 2>&1 || fail "curl 명령이 없어 HTTP 점검 불가"

# ---------- 1. 부팅 정보 ----------
head1 "1. 시스템 부팅 정보"
if [ -r /proc/uptime ]; then
  UP_SEC=$(cut -d. -f1 /proc/uptime); UP_MIN=$((UP_SEC/60))
  BOOT_AT=$(date -d "@$(( $(date +%s) - UP_SEC ))" '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo "?")
  ok "마지막 부팅: ${BOOT_AT} (경과 ${UP_MIN}분 / $((UP_MIN/60))시간)"
  [ "$UP_MIN" -lt 10 ] && info "방금 재부팅된 상태입니다. 서버가 아직 올라오는 중일 수 있습니다 (--wait 300 권장)."
else
  UP_MIN=99999; warn "/proc/uptime 을 읽을 수 없어 부팅 시각 확인 불가"
fi

# ---------- 2. 서버 기동 대기 (옵션) ----------
if [ "$WAIT_SEC" -gt 0 ]; then
  head1 "2. 서버 기동 대기 (최대 ${WAIT_SEC}초)"
  START=$(date +%s)
  while :; do
    if probe_json "${HTTPS_URL%/}${HEALTH_PATH}"; then
      ok "서버 응답 확인 ($(( $(date +%s) - START ))초 만에)"; break
    fi
    if [ $(( $(date +%s) - START )) -ge "$WAIT_SEC" ]; then
      warn "${WAIT_SEC}초 대기했지만 서버가 응답하지 않음 ($(probe_why))"; break
    fi
    sleep 5
  done
fi

# ---------- 3. PM2 프로세스 ----------
head1 "3. PM2 프로세스 (${PM2_APP})"
PM2_BIN="${SN_PM2_BIN:-}"
if [ -z "$PM2_BIN" ]; then
  for p in /usr/local/bin/pm2 /usr/bin/pm2 "$ROOT/node_modules/.bin/pm2"; do
    [ -x "$p" ] && PM2_BIN="$p" && break
  done
fi
[ -z "$PM2_BIN" ] && PM2_BIN=$(command -v pm2 2>/dev/null)

if [ -z "$PM2_BIN" ]; then
  fail "pm2 를 찾을 수 없음 (PATH/Node 설치 확인)"
elif ! command -v node >/dev/null 2>&1; then
  warn "node 를 찾을 수 없어 pm2 상태 파싱 불가 — 'pm2 list' 로 직접 확인하세요"
else
  PM2_LIST=$("$PM2_BIN" jlist 2>/dev/null | grep -E '^\[(\{|\])' | tail -1 | node -e '
    let d="";process.stdin.on("data",c=>d+=c).on("end",()=>{
      try{JSON.parse(d).forEach(p=>{const e=p.pm2_env||{};
        console.log([p.name,e.status,e.restart_time,Math.max(0,Math.round((Date.now()-e.pm_uptime)/1000))].join("|"))})}catch(x){}})' 2>/dev/null)
  if [ -z "$PM2_LIST" ]; then
    fail "pm2 에 등록된 프로세스가 없음 → 부팅 시 'pm2 resurrect' 가 실행되지 않았을 가능성. 수동 복구: export HOME=/root; pm2 resurrect"
  else
    LINE=$(echo "$PM2_LIST" | awk -F'|' -v n="$PM2_APP" '$1==n{print; exit}')
    if [ -z "$LINE" ]; then
      fail "pm2 목록에 '${PM2_APP}' 없음 (등록된 앱: $(echo "$PM2_LIST" | cut -d'|' -f1 | tr '\n' ' '))"
    else
      IFS='|' read -r pn pst prs pup <<< "$LINE"
      if [ "$pst" = "online" ]; then ok "${pn}: online (재시작 ${prs}회, 가동 ${pup}초)"
      else fail "${pn}: 상태 '${pst}' (online 아님)"; fi
      [ "${prs:-0}" -ge 5 ] && warn "${pn}: 재시작 ${prs}회 — 반복 crash 가능성. pm2 logs ${pn} --nostream --lines 50"
      # 부팅 직후인데 가동시간이 부팅 경과보다 훨씬 길면 → 재부팅과 무관하게 유지된 프로세스
      [ "$pst" = "online" ] && [ "$pup" -gt $((UP_SEC + 120)) ] 2>/dev/null && info "참고: 가동시간이 부팅 경과보다 김 (시스템 재부팅 아님, 수동 재시작 등)"
    fi
    # 비상복구 서버(선택): 있으면 상태만 표시
    REC=$(echo "$PM2_LIST" | awk -F'|' '$1 ~ /recovery/{print; exit}')
    [ -n "$REC" ] && { IFS='|' read -r rn rst _ _ <<< "$REC"; info "비상복구 ${rn}: ${rst}"; }
  fi
fi

# ---------- 4. 부팅 자동기동 설정 ----------
head1 "4. 부팅 자동기동 설정"
DUMP="$HOME/.pm2/dump.pm2"
if [ -s "$DUMP" ]; then
  if grep -q "\"name\": *\"${PM2_APP}\"" "$DUMP" 2>/dev/null; then ok "pm2 저장 목록(dump.pm2)에 ${PM2_APP} 있음 (pm2 resurrect 대상)"
  else warn "dump.pm2 에 ${PM2_APP} 없음 → 서버 구성 변경 후 'pm2 save' 필요"; fi
else
  warn "$DUMP 없음/비어 있음 → 'pm2 save' 필요 (재부팅 시 자동기동 불가)"
fi
BOOTLOG=""
for f in /var/log/safetynote-boot.log "$ROOT/safetynote-boot.log"; do [ -f "$f" ] && BOOTLOG="$f" && break; done
if [ -n "$BOOTLOG" ]; then
  ok "부팅 작업 로그: $BOOTLOG (수정 $(date -r "$BOOTLOG" '+%m-%d %H:%M' 2>/dev/null))"
  tail -n 3 "$BOOTLOG" 2>/dev/null | while IFS= read -r l; do info "│ $l"; done
else
  info "safetynote-boot.log 없음 — 'SafetyNOTE Bootin Start' 작업 로그 경로가 다르면 무시"
fi

# ---------- 5. 포트 / 서버 응답 ----------
head1 "5. 포트 / 서버 응답"
if command -v ss >/dev/null 2>&1; then LISTEN=$(ss -ltn 2>/dev/null)
elif command -v netstat >/dev/null 2>&1; then LISTEN=$(netstat -ltn 2>/dev/null)
else LISTEN=""; fi
if [ -n "$LISTEN" ]; then
  MAIN_PORT=$(echo "$HTTPS_URL" | sed -n 's#^[a-z]*://[^:/]*:\([0-9]*\).*#\1#p'); MAIN_PORT="${MAIN_PORT:-3443}"
  FCM_PORT=$(echo "$HTTP_URL" | sed -n 's#^[a-z]*://[^:/]*:\([0-9]*\).*#\1#p'); FCM_PORT="${FCM_PORT:-3444}"
  if echo "$LISTEN" | grep -qE "[:.]${MAIN_PORT}[[:space:]]"; then ok "포트 ${MAIN_PORT} 리스닝 중"
  else fail "포트 ${MAIN_PORT} 리스닝 안 함"; fi
  if echo "$LISTEN" | grep -qE "[:.]${FCM_PORT}[[:space:]]"; then ok "포트 ${FCM_PORT}(FCM 내부 HTTP) 리스닝 중"
  else warn "포트 ${FCM_PORT}(FCM 내부 HTTP) 리스닝 안 함"; fi
fi

if probe_json "${HTTPS_URL%/}${HEALTH_PATH}"; then
  GH=$(json_field git_hash); TZV=$(json_field server_tz); NOW=$(json_field now_kst)
  ok "서버 정상 응답: ${HTTPS_URL}${HEALTH_PATH} (HTTP ${HC_CODE})"
  info "서버 시각(KST): ${NOW:-?} / 타임존: ${TZV:-?} / git: ${GH:0:7}"
  # 서버가 실행 중인 코드와 디스크의 최신 코드 비교
  if [ -d "$ROOT/.git" ] && command -v git >/dev/null 2>&1; then
    DISK_GH=$(git -C "$ROOT" rev-parse HEAD 2>/dev/null)
    if [ -n "$DISK_GH" ] && [ -n "$GH" ]; then
      if [ "$DISK_GH" = "$GH" ]; then ok "실행 중 코드 = 폴더 코드 (${GH:0:7})"
      else warn "실행 중 코드(${GH:0:7}) ≠ 폴더 코드(${DISK_GH:0:7}) → git pull 후 pm2 restart 안 한 상태일 수 있음"; fi
    fi
  fi
else
  fail "서버 응답 이상: ${HTTPS_URL}${HEALTH_PATH} → $(probe_why)"
fi

# FCM 내부 HTTP 포트 (응답 여부만)
HCODE=$(curl -s -m "$CURL_TIMEOUT" -o /dev/null -w '%{http_code}' "${HTTP_URL%/}${HEALTH_PATH}" 2>/dev/null)
if [[ "$HCODE" =~ ^2 ]]; then ok "FCM 내부 HTTP 포트 응답 (${HTTP_URL})"
else warn "FCM 내부 HTTP 포트 응답 없음 (${HTTP_URL}, HTTP ${HCODE:-000}) — 푸시 알림 등록에 영향"; fi

# 외부 주소 (공유기 포트포워딩/DDNS). NAS 내부에서는 NAT 루프백 때문에 실패할 수 있어 경고만.
if [ "$PUBLIC_URL" != "none" ] && [ -n "$PUBLIC_URL" ]; then
  if probe_json "${PUBLIC_URL%/}${HEALTH_PATH}"; then ok "외부 주소 응답 정상: ${PUBLIC_URL}"
  else warn "외부 주소 응답 이상: ${PUBLIC_URL} → $(probe_why) (NAS 내부에서는 공유기 루프백 미지원으로 실패할 수 있음 — 스마트폰 LTE 로 접속 확인 권장)"; fi
fi

# ---------- 6. 볼륨 / 데이터 폴더 ----------
head1 "6. 볼륨 / 데이터 폴더"
for d in $DATA_DIRS; do
  if [ -d "$d" ]; then
    if [ -w "$d" ]; then ok "$d : 존재, 쓰기 가능"; else warn "$d : 존재하지만 쓰기 불가"; fi
  else fail "$d : 폴더 없음 (볼륨 마운트 실패 가능성)"; fi
done
FIRSTDIR=$(echo "$DATA_DIRS" | awk '{print $1}')
if [ -d "$FIRSTDIR" ]; then
  PCT=$(df -P "$FIRSTDIR" 2>/dev/null | awk 'NR==2{gsub("%","",$5); print $5}')
  if [ -n "$PCT" ]; then
    if [ "$PCT" -ge 90 ]; then fail "볼륨 사용률 ${PCT}% (90% 이상)"
    elif [ "$PCT" -ge 80 ]; then warn "볼륨 사용률 ${PCT}% (80% 이상)"
    else ok "볼륨 사용률 ${PCT}%"; fi
  fi
fi

# ---------- 7. 스냅샷 ----------
head1 "7. 스냅샷"
for d in $SNAPSHOT_DIRS; do
  if [ -d "$d" ]; then
    CNT=$(ls "$d" 2>/dev/null | wc -l)
    ok "$d : ${CNT}개"
    [ "$CNT" -ge 100 ] && warn "$d : 스냅샷 100개(보존 한도) 도달 — 용량 확인"
  else info "$d : 폴더 없음(건너뜀)"; fi
done

# ---------- 8. 감시(watchdog) 로그 ----------
head1 "8. 감시 작업 로그 (최근 기록)"
WDLOG=""
for f in /var/log/safetynote-watchdog.log; do [ -f "$f" ] && WDLOG="$f"; done
if [ -n "$WDLOG" ]; then
  info "$WDLOG (수정 $(date -r "$WDLOG" '+%m-%d %H:%M' 2>/dev/null))"
  tail -n 3 "$WDLOG" 2>/dev/null | while IFS= read -r l; do info "│ $l"; done
  tail -n 20 "$WDLOG" 2>/dev/null | grep -qiE "rollback|롤백|RECOVERY.*시작|crash" && warn "watchdog 로그에 rollback/recovery/crash 기록 있음 — 내용 확인 필요"
else
  info "watchdog 로그 없음"
fi

# ---------- 결과 ----------
out ""
out "============================================================"
if [ "$FAILS" -gt 0 ]; then RESULT="❌ 장애 (실패 ${FAILS}, 경고 ${WARNS})"; CODE=2
elif [ "$WARNS" -gt 0 ]; then RESULT="⚠️  정상(경고 ${WARNS}건)"; CODE=1
else RESULT="✅ 모두 정상"; CODE=0; fi
out "결과: ${RESULT}  [$(ts)]"
if [ -n "$SUMMARY" ]; then out "확인 필요 항목:"; out "$(printf '%b' "$SUMMARY" | sed '/^$/d')"; fi
out "로그: $LOG_FILE"
out "============================================================"
[ "$QUIET" -eq 1 ] && echo "[$(ts)] Safety NOTE 점검 결과: ${RESULT}"
exit "$CODE"
