#!/usr/bin/env bash
# A1 崩溃恢复演练：盯住某条规则的执行，一旦最新一条出现 RUNNING 就 kill -9 目标进程。
#
# 为什么不能随手 kill：监控的一次执行只持续约 0.5~0.7s，随手 kill 大概率扑空，
# 只能验证「重启后调度恢复」，验证不到「在途执行的租约回收」。
# 所以本脚本高频轮询 executions。注意日志里「等待中 20s」是**报告间隔**，不是轮询间隔；
# 每行会同时打印已采样次数与平均间隔，用它判断采样是否足够密。
#
# 用法（在跑服务的机器上执行）：
#   MONITOR_ACCESS_TOKEN='<32位>' ./monitor_kill_watch.sh \
#       --visitor <该规则的访客 cookie> --rule <ruleId> --match cexpilot
#
#   只观察不杀（先空跑一遍确认能抓到窗口）：
#   ./monitor_kill_watch.sh --visitor xxx --rule yyy --observe
#
# 参数：
#   --base <url>       默认 https://cex.hrscrm.net。强烈建议传 http://127.0.0.1:<port>：
#                      走公网 HTTPS 单次往返 100~300ms，0.6s 的窗口只能采到 2~3 次
#   --visitor <uid>    创建该规则时的 cexpilot_uid（stop/查询都按 owner 隔离）
#   --rule <ruleId>
#   --pid <pid>        直接指定进程号（优先于 --match）
#   --match <pattern>  用 pgrep -f 找进程，例如 cexpilot / 'java -jar'
#   --max-wait <秒>    最长等待，默认 480（够跨 1~2 个 5 分钟周期）
#   --poll <秒>        轮询间隔，默认 0.05
#   --report <秒>      进度打印间隔，默认 20
#   --dispatch-delay <秒>  slot 之后多久派发，默认 20（= publication-delay），只用于提示「下一次预计在几点」
#   --observe          只报告 RUNNING 时刻，不杀进程
#   --grace <秒>       命中后等几秒再杀，默认 0。执行只持续约 0.5s，任何等待都会错过窗口
set -u

BASE="${BASE:-https://cex.hrscrm.net}"
TOKEN="${MONITOR_ACCESS_TOKEN:-}"
VISITOR=""
RULE=""
PID=""
MATCH=""
MAXWAIT=480
POLL=0.05
REPORT=20
DISPATCH_DELAY=20
OBSERVE=0
GRACE=0

usage() {
  sed -n '2,/^set -u/p' "$0" | grep '^#' | sed 's/^# \{0,1\}//'
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
    --poll) POLL="$2"; shift 2 ;;
    --report) REPORT="$2"; shift 2 ;;
    --dispatch-delay) DISPATCH_DELAY="$2"; shift 2 ;;
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
echo "规则 $RULE   最长等待 ${MAXWAIT}s   轮询 ${POLL}s   命中后宽限 ${GRACE}s"
case "$BASE" in
  *127.0.0.1*|*localhost*) ;;
  *) echo "提示：--base 不是本机地址，单次往返约 100~300ms，0.6s 的执行窗口只能采到 2~3 次。" \
          "若本轮没抓到，改用 --base http://127.0.0.1:<port> 重跑。" ;;
esac
echo "---"

start=$(date +%s)
last_report=0
samples=0
while :; do
  body=$(curl -s -m 5 -H "Cookie: cexpilot_uid=$VISITOR" "$URL" | tr -d ' \n')
  samples=$((samples + 1))
  if [ -z "$body" ] || [ "${body#\[}" = "$body" ]; then
    echo "读取 executions 失败（HTTP/JSON 异常）：$body" >&2
    exit 4
  fi

  # 只看最新一条（列表按 scheduled_at DESC）：若历史遗留孤儿 RUNNING，用「任意一条 RUNNING」
  # 判断会立刻误触发，把 kill 打在错误的时刻上。
  newest=$(echo "$body" | grep -o '"status":"[A-Z]*"' | head -1 | sed 's/.*"status":"//;s/"$//')

  if [ "$newest" = "RUNNING" ]; then
      stamp=$(date -u +%H:%M:%S.%3NZ 2>/dev/null || date -u +%H:%M:%SZ)
      echo "[$stamp] 捕获 RUNNING"
      echo "$body" > /tmp/monitor_kill_snapshot.json
      echo "  快照已存 /tmp/monitor_kill_snapshot.json"
      echo "$body" | grep -o '"scheduledAt":"[^"]*","deadlineAt":"[^"]*"' | head -1
      if [ "$OBSERVE" -eq 1 ]; then
        echo "  --observe 模式：不杀进程，退出"
        exit 0
      fi
      if [ "$GRACE" -gt 0 ]; then
        echo "  ${GRACE}s 后 kill -9 $PID（Ctrl-C 可放弃）"
        sleep "$GRACE"
      fi
      kill -9 "$PID" && echo "  [$(date -u +%H:%M:%S.%3NZ 2>/dev/null || date -u +%H:%M:%SZ)] 已发送 SIGKILL 到 $PID"
      echo "  现在请在 5 分钟内重启（slot 截止 = scheduledAt + 300s，超时该 slot 记 EXECUTION_EXPIRED）"
      exit 0
  fi

  now=$(date +%s)
  elapsed=$((now - start))
  if [ $((elapsed - last_report)) -ge "$REPORT" ]; then
    last_report=$elapsed
    recent=$(echo "$body" | grep -o '"status":"[A-Z]*"' | head -3 | sed 's/"status":"//;s/"//' | tr '\n' ' ')
    slot=$(echo "$body" | grep -o '"scheduledAt":"[^"]*"' | head -1 | cut -d'"' -f4)
    # 采样次数与平均间隔必须打出来：只报「等待中 20s」会让人误以为是 20s 轮询一次
    avg=0
    [ "$samples" -gt 0 ] && avg=$((elapsed * 1000 / samples))
    echo "[$(date -u +%H:%M:%SZ)] 等待中 ${elapsed}s | 已采样 ${samples} 次（平均 ${avg}ms/次）| 最新 slot ${slot:-无} ${recent:-}"
    if [ -n "$slot" ]; then
      slot_epoch=$(date -u -d "$slot" +%s 2>/dev/null || echo 0)
      if [ "$slot_epoch" -gt 0 ]; then
        nxt=$((slot_epoch + 300 + "$DISPATCH_DELAY"))
        left=$((nxt - now))
        if [ "$left" -gt 0 ]; then
          echo "           下一次预计在 $(date -u -d "@$nxt" +%H:%M:%SZ)（slot+${DISPATCH_DELAY}s），还有 ${left}s"
        fi
      fi
    fi
  fi
  if [ "$elapsed" -ge "$MAXWAIT" ]; then
    echo "超时 ${MAXWAIT}s 没抓到 RUNNING 窗口。可选：调大 --max-wait，或接受「空闲 kill」——"
    echo "那只能验证重启后调度恢复，验证不到在途执行的接管。"
    exit 3
  fi
  sleep "$POLL"
done
