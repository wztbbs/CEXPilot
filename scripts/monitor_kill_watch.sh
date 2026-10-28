#!/usr/bin/env bash
# A1 崩溃恢复演练：盯住某条规则的执行，一旦出现 RUNNING 就 kill -9 目标进程。
#
# 为什么不能随手 kill：监控的执行只持续几百毫秒到一两秒，随手 kill 大概率扑空，
# 只能验证「重启后调度恢复」，验证不到「在途执行的租约接管与 fencing」。
# 本脚本以 0.2s 间隔轮询 executions，命中 RUNNING 立刻动手，把窗口钉住。
#
# 用法（在跑服务的机器上执行）：
#   MONITOR_ACCESS_TOKEN='<32位>' ./monitor_kill_watch.sh \
#       --visitor <该规则的访客 cookie> --rule <ruleId> --match cexpilot
#
#   只观察不杀（先空跑一遍确认能抓到窗口）：
#   ./monitor_kill_watch.sh --visitor xxx --rule yyy --observe
#
# 参数：
#   --base <url>       默认 https://cex.hrscrm.net，服务端本机可传 http://127.0.0.1:<port>
#   --visitor <uid>    创建该规则时的 cexpilot_uid（stop/查询都按 owner 隔离）
#   --rule <ruleId>
#   --pid <pid>        直接指定进程号（优先于 --match）
#   --match <pattern>  用 pgrep -f 找进程，例如 cexpilot / 'java -jar'
#   --max-wait <秒>    最长等待，默认 480（够跨 1~2 个 5 分钟周期）
#   --observe          只报告 RUNNING 时刻，不杀进程
#   --grace <秒>       命中后倒计时几秒再杀，默认 2（留出 Ctrl-C 的机会）
set -u

BASE="${BASE:-https://cex.hrscrm.net}"
TOKEN="${MONITOR_ACCESS_TOKEN:-}"
VISITOR=""
RULE=""
PID=""
MATCH=""
MAXWAIT=480
POLL=0.2
OBSERVE=0
GRACE=2

usage() {
  sed -n '2,26p' "$0" | sed 's/^# \{0,1\}//'
}

while [ $# -gt 0 ]; do
  case "$1" in
    --base) BASE="$2"; shift 2 ;;
    --visitor) VISITOR="$2"; shift 2 ;;
    --rule) RULE="$2"; shift 2 ;;
    --pid) PID="$2"; shift 2 ;;
    --match) MATCH="$2"; shift 2 ;;
    --max-wait) MAXWAIT="$2"; shift 2 ;;
    --grace) GRACE="$2"; shift 2 ;;
    --observe) OBSERVE=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "未知参数：$1（--help 看用法）" >&2; exit 2 ;;
  esac
done

[ -n "$VISITOR" ] || { echo "缺少 --visitor" >&2; exit 2; }
[ -n "$RULE" ] || { echo "缺少 --rule" >&2; exit 2; }

if [ "$OBSERVE" -eq 0 ]; then
  if [ -z "$PID" ] && [ -n "$MATCH" ]; then
    PID=$(pgrep -f "$MATCH" | head -1)
  fi
  if [ -z "$PID" ]; then
    echo "缺少 --pid 或 --match（或用 --observe 只看不杀）" >&2; exit 2
  fi
  if ! ps -p "$PID" > /dev/null 2>&1; then
    echo "进程 $PID 不存在，先确认目标进程号" >&2; exit 2
  fi
  echo "目标进程："
  ps -p "$PID" -o pid=,etime=,command= | cut -c1-160
fi

URL="$BASE/api/monitor-flow/rules/$RULE/executions"
echo "规则 $RULE   最长等待 ${MAXWAIT}s"
echo "---"

start=$(date +%s)
last_report=0
while :; do
  body=$(curl -s -m 5 -H "Cookie: cexpilot_uid=$VISITOR" "$URL" | tr -d ' \n')
  if [ -z "$body" ] || [ "${body#\[}" = "$body" ]; then
    echo "读取 executions 失败（HTTP/JSON 异常）：$body" >&2
    exit 4
  fi

  case "$body" in
    *'"status":"RUNNING"'*)
      stamp=$(date -u +%H:%M:%SZ)
      echo "[$stamp] 捕获 RUNNING"
      echo "$body" > /tmp/monitor_kill_snapshot.json
      echo "  快照已存 /tmp/monitor_kill_snapshot.json"
      echo "$body" | grep -o '"scheduledAt":"[^"]*","deadlineAt":"[^"]*"[^}]*"status":"RUNNING"' | head -1
      if [ "$OBSERVE" -eq 1 ]; then
        echo "  --observe 模式：不杀进程，退出"
        exit 0
      fi
      echo "  ${GRACE}s 后 kill -9 $PID（Ctrl-C 可放弃）"
      sleep "$GRACE"
      kill -9 "$PID" && echo "  已发送 SIGKILL 到 $PID"
      echo "  现在请在 5 分钟内重启（slot 截止 = scheduledAt + 300s，超时该 slot 记 EXECUTION_EXPIRED）"
      exit 0
      ;;
  esac

  now=$(date +%s)
  elapsed=$((now - start))
  if [ $((elapsed - last_report)) -ge 20 ]; then
    last_report=$elapsed
    recent=$(echo "$body" | grep -o '"status":"[A-Z]*"' | head -3 | sed 's/"status":"//;s/"//' | tr '\n' ' ')
    echo "[$(date -u +%H:%M:%SZ)] 等待中 ${elapsed}s，最近状态：${recent:-无}"
  fi
  if [ "$elapsed" -ge "$MAXWAIT" ]; then
    echo "超时 ${MAXWAIT}s 没抓到 RUNNING 窗口。可选：调大 --max-wait，或接受「空闲 kill」——"
    echo "那只能验证重启后调度恢复，验证不到在途执行的接管。"
    exit 3
  fi
  sleep "$POLL"
done
