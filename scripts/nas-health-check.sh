#!/bin/bash
# ============================================================
# Safety NOTE - NAS 재부팅 후 서비스 자동 기동 점검 스크립트
#
# 사용법
#   bash nas-health-check.sh              # 1회 점검
#   bash nas-health-check.sh --wait 300   # 최대 300초까지 서비스 기동 대기 후 점검 (부팅 직후용)
#   bash nas-health-check.sh --quiet      # 요약만 출력 (작업 스케줄러용, 로그 파일엔 전체 기록)
#
# 종료 코드: 0 = 정상 / 1 = 경고 있음 / 2 = 장애(서비스 응답 없음 등)
#
# 환경변수로 NAS 환경에 맞게 조정 가능 (모두 선택 사항)
#   SN_ENDPOINTS      점검할 서버 URL 목록 (공백 구분)
#                     기본: http://localhost:3000 http://localhost:3444 https://localhost:3443
#   SN_REQUIRE        any = 하나라도 응답하면 정상 (기본) / all = 전부 응답해야 정상
#   SN_HEALTH_PATH    헬스체크 경로 (기본 /api/health)
#   SN_SERVICE_NAME   Docker 컨테이너 이름 필터 (기본 safetynote)
#   SN_DATA_DIRS      존재/쓰기 확인할 폴더 (기본 /volume1/safetynote /volume1/safetynote_data)
#   SN_SNAPSHOT_DIRS  스냅샷 개수 확인할 폴더
#   SN_MIN_UPTIME_MIN 이 값(분)보다 부팅 경과가 짧으면 "방금 재부팅" 으로 표시 (기본 10)
#   SN_LOG            로그 파일 (기본 /var/log/safetynote-healthcheck.log, 쓰기 불가 시 /tmp)
# ============================================================

ENDPOINTS="${SN_ENDPOINTS:-http://localhost:3000 http://localhost:3444 https://localhost:3443}"
REQUIRE="${SN_REQUIRE:-any}"
HEALTH_PATH="${SN_HEALTH_PATH:-/api/health}"
SERVICE_NAME="${SN_SERVICE_NAME:-safetynote}"
DATA_DIRS="${SN_DATA_DIRS:-/volume1/safetynote /volume1/safetynote_data}"
SNAPSHOT_DIRS="${SN_SNAPSHOT_DIRS:-/volume1/safetynote/#snapshot /volume1/safetynote_data/#snapshot}"
MIN_UPTIME_MIN="${SN_MIN_UPTIME_MIN:-10}"
LOG_FILE="${SN_LOG:-/var/log/safetynote-healthcheck.log}"
CURL_TIMEOUT=5

WAIT_SEC=0
QUIET=0
while [ $# -gt 0 ]; do
  case "$1" in
    --wait)  WAIT_SEC="${2:-300}"; shift 2 ;;
    --quiet) QUIET=1; shift ;;
    -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
    *) echo "알 수 없는 옵션: $1 (--help 참고)"; exit 64 ;;
  esac
done

# 로그 파일 쓰기 가능 여부 확인, 안 되면 /tmp 로 대체
if ! touch "$LOG_FILE" 2>/dev/null; then
  LOG_FILE="/tmp/safetynote-healthcheck.log"
  touch "$LOG_FILE" 2>/dev/null || LOG_FILE="/dev/null"
fi

FAILS=0
WARNS=0
SUMMARY=""

out()  { echo "$1" >> "$LOG_FILE"; [ "$QUIET" -eq 0 ] && echo "$1"; return 0; }
ts()   { date '+%Y-%m-%d %H:%M:%S'; }
ok()   { out "  ✅ $1"; }
warn() { out "  ⚠️  $1"; WARNS=$((WARNS+1)); SUMMARY="${SUMMARY}\n  ⚠️  $1"; }
fail() { out "  ❌ $1"; FAILS=$((FAILS+1)); SUMMARY="${SUMMARY}\n  ❌ $1"; }
info() { out "     $1"; }
head1(){ out ""; out "[$1]"; }

http_code() {
  local code
  code=$(curl -k -s -o /dev/null -m "$CURL_TIMEOUT" -w '%{http_code}' "$1" 2>/dev/null)
  [ -z "$code" ] && code="000"
  echo "$code"
}

# 엔드포인트 점검: OK 개수를 EP_OK, 전체 개수를 EP_TOTAL 에 저장
check_endpoints() {
  EP_OK=0; EP_TOTAL=0; EP_REPORT=""
  for base in $ENDPOINTS; do
    EP_TOTAL=$((EP_TOTAL+1))
    local url="${base%/}${HEALTH_PATH}"
    local code
    code=$(http_code "$url")
    if [[ "$code" =~ ^2[0-9][0-9]$ ]]; then
      EP_OK=$((EP_OK+1))
      EP_REPORT="${EP_REPORT}OK|$url|$code\n"
    elif [ "$code" = "000" ]; then
      EP_REPORT="${EP_REPORT}DOWN|$url|응답 없음(연결 불가)\n"
    else
      EP_REPORT="${EP_REPORT}BAD|$url|HTTP $code\n"
    fi
  done
}

endpoints_pass() {
  if [ "$REQUIRE" = "all" ]; then [ "$EP_OK" -eq "$EP_TOTAL" ]; else [ "$EP_OK" -ge 1 ]; fi
}

out "============================================================"
out "Safety NOTE 서비스 점검 시작: $(ts)  (host: $(hostname))"
out "============================================================"

if ! command -v curl >/dev/null 2>&1; then
  fail "curl 명령이 없어 HTTP 점검 불가"
fi

# ---------- 1. 부팅 정보 ----------
head1 "1. 시스템 부팅 정보"
if [ -r /proc/uptime ]; then
  UP_SEC=$(cut -d. -f1 /proc/uptime)
  UP_MIN=$((UP_SEC/60))
  BOOT_AT=$(date -d "@$(( $(date +%s) - UP_SEC ))" '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo "?")
  ok "마지막 부팅: ${BOOT_AT} (경과 ${UP_MIN}분 / $((UP_MIN/60))시간)"
  if [ "$UP_MIN" -lt "$MIN_UPTIME_MIN" ]; then
    info "방금 재부팅된 상태입니다. 서비스가 아직 올라오는 중일 수 있습니다 (--wait 300 권장)."
  fi
else
  UP_MIN=99999
  warn "/proc/uptime 을 읽을 수 없어 부팅 시각 확인 불가"
fi

# ---------- 2. 서비스 기동 대기 (옵션) ----------
if [ "$WAIT_SEC" -gt 0 ]; then
  head1 "2. 서비스 기동 대기 (최대 ${WAIT_SEC}초)"
  START=$(date +%s)
  while :; do
    check_endpoints
    if endpoints_pass; then
      ok "서비스 응답 확인 ($(( $(date +%s) - START ))초 만에)"
      break
    fi
    if [ $(( $(date +%s) - START )) -ge "$WAIT_SEC" ]; then
      warn "${WAIT_SEC}초 대기했지만 서비스가 응답하지 않음"
      break
    fi
    sleep 5
  done
fi

# ---------- 3. Docker / Container Manager ----------
head1 "3. Docker / Container Manager"
if command -v synopkg >/dev/null 2>&1; then
  for pkg in ContainerManager Docker; do
    st=$(synopkg status "$pkg" 2>/dev/null)
    if [ -n "$st" ]; then
      if echo "$st" | grep -q '"status":"running"\|"status": "running"'; then
        ok "패키지 $pkg: 실행 중"
      else
        fail "패키지 $pkg: 실행 중이 아님 → 패키지 센터에서 확인 필요"
      fi
      break
    fi
  done
fi

if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  ALL=$(docker ps -a --format '{{.Names}}|{{.State}}|{{.Status}}' 2>/dev/null)
  MATCH=$(echo "$ALL" | grep -i "$SERVICE_NAME")
  if [ -z "$MATCH" ]; then
    warn "이름에 '$SERVICE_NAME' 이 들어간 컨테이너 없음 (Docker 미사용이면 무시) — 전체 컨테이너:"
    [ -n "$ALL" ] && echo "$ALL" | while IFS='|' read -r n s st; do info "- $n [$s] $st"; done
  else
    echo "$MATCH" | while IFS='|' read -r n s st; do
      if [ "$s" = "running" ]; then
        echo "RUN|$n|$st"
      else
        echo "STOP|$n|$st"
      fi
    done > /tmp/.sn_container_state.$$
    while IFS='|' read -r kind n st; do
      pol=$(docker inspect -f '{{.HostConfig.RestartPolicy.Name}}' "$n" 2>/dev/null)
      if [ "$kind" = "RUN" ]; then
        ok "컨테이너 $n: 실행 중 ($st)"
      else
        fail "컨테이너 $n: 중지됨 ($st)"
      fi
      case "$pol" in
        always|unless-stopped) ok "컨테이너 $n: 재시작 정책 = $pol (재부팅 시 자동 기동)" ;;
        *) fail "컨테이너 $n: 재시작 정책 = '${pol:-no}' → 재부팅 후 자동 기동 안 됨. docker update --restart unless-stopped $n" ;;
      esac
    done < /tmp/.sn_container_state.$$
    rm -f /tmp/.sn_container_state.$$
  fi
else
  info "Docker 사용 불가/미설치 → 컨테이너 점검 건너뜀"
fi

# ---------- 4. 프로세스 (Docker 미사용 시 참고) ----------
head1 "4. 서버 프로세스"
PROCS=$(ps -eo pid,etime,args 2>/dev/null | grep -iE "node|safetynote|pm2" | grep -v grep | grep -v nas-health-check | head -5)
if [ -n "$PROCS" ]; then
  ok "node/safetynote 관련 프로세스 발견"
  echo "$PROCS" | while read -r line; do info "$line"; done
else
  info "호스트에서 node/safetynote 프로세스 없음 (Docker 컨테이너 내부 실행이면 정상)"
fi

# ---------- 5. 포트 리스닝 + HTTP 헬스체크 ----------
head1 "5. 포트 / HTTP 헬스체크 (${HEALTH_PATH})"
check_endpoints
printf '%b' "$EP_REPORT" | while IFS='|' read -r kind url msg; do
  [ -z "$kind" ] && continue
  case "$kind" in
    OK)   out "  ✅ $url → HTTP $msg" ;;
    DOWN) out "  ❌ $url → $msg" ;;
    BAD)  out "  ⚠️  $url → $msg" ;;
  esac
done
if endpoints_pass; then
  ok "서비스 정상 응답 (${EP_OK}/${EP_TOTAL}, 기준: ${REQUIRE})"
  [ "$EP_OK" -lt "$EP_TOTAL" ] && warn "일부 엔드포인트만 응답 (${EP_OK}/${EP_TOTAL}) — 사용하지 않는 포트면 SN_ENDPOINTS 로 제외하세요"
else
  fail "서비스 응답 없음 (${EP_OK}/${EP_TOTAL}, 기준: ${REQUIRE})"
fi

# ---------- 6. 데이터 폴더 / 디스크 ----------
head1 "6. 볼륨 / 데이터 폴더"
for d in $DATA_DIRS; do
  if [ -d "$d" ]; then
    if [ -w "$d" ]; then ok "$d : 존재, 쓰기 가능"; else warn "$d : 존재하지만 쓰기 불가"; fi
  else
    fail "$d : 폴더 없음 (볼륨 마운트 실패 가능성)"
  fi
done
VOL=$(echo "$DATA_DIRS" | awk '{print $1}')
if [ -d "$VOL" ]; then
  USE=$(df -P "$VOL" 2>/dev/null | awk 'NR==2{gsub("%","",$5); print $5" "$4}')
  PCT=${USE%% *}
  if [ -n "$PCT" ]; then
    if [ "$PCT" -ge 90 ]; then fail "볼륨 사용률 ${PCT}% (90% 이상)";
    elif [ "$PCT" -ge 80 ]; then warn "볼륨 사용률 ${PCT}% (80% 이상)";
    else ok "볼륨 사용률 ${PCT}%"; fi
  fi
fi

# ---------- 7. 스냅샷 ----------
head1 "7. 스냅샷"
for d in $SNAPSHOT_DIRS; do
  if [ -d "$d" ]; then
    CNT=$(ls "$d" 2>/dev/null | wc -l)
    LATEST=$(ls -t "$d" 2>/dev/null | head -1)
    ok "$d : ${CNT}개 (최신: ${LATEST:-없음})"
    [ "$CNT" -ge 100 ] && warn "$d : 스냅샷 100개 도달 (보존 한도) — 용량 확인"
  else
    info "$d : 폴더 없음(건너뜀)"
  fi
done

# ---------- 8. 자동 배포 크론 ----------
head1 "8. 자동 배포 크론 (nas-auto-deploy.sh)"
if grep -rqs "nas-auto-deploy" /etc/crontab /etc/cron.d 2>/dev/null; then
  ok "크론에 nas-auto-deploy 등록됨"
else
  info "/etc/crontab 에 없음 (DSM 작업 스케줄러로 등록했다면 무시)"
fi

# ---------- 결과 ----------
out ""
out "============================================================"
if [ "$FAILS" -gt 0 ]; then
  RESULT="❌ 장애 (실패 ${FAILS}, 경고 ${WARNS})"; CODE=2
elif [ "$WARNS" -gt 0 ]; then
  RESULT="⚠️  정상(경고 ${WARNS}건)"; CODE=1
else
  RESULT="✅ 모두 정상"; CODE=0
fi
out "결과: ${RESULT}  [$(ts)]"
if [ -n "$SUMMARY" ]; then
  out "확인 필요 항목:"
  out "$(printf '%b' "$SUMMARY" | sed '/^$/d')"
fi
out "로그: $LOG_FILE"
out "============================================================"

# --quiet 모드에서도 결과 한 줄은 화면에 출력
[ "$QUIET" -eq 1 ] && echo "[$(ts)] Safety NOTE 점검 결과: ${RESULT}"
exit "$CODE"
