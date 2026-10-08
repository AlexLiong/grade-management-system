#!/usr/bin/env bash
# Stop the local stack started by scripts/start.sh (macOS / Linux).
#
# Usage:
#   ./scripts/stop.sh                  # stop the 4 Java services + chain-worker
#   ./scripts/stop.sh --keep-chain     # stop only the 4 Java services
#   ./scripts/stop.sh --include-vite   # also stop the Vite dev server (port 5173)
#   ./scripts/stop.sh --list           # only show what is running, stop nothing
#   ./scripts/stop.sh --force          # SIGKILL immediately instead of SIGTERM
#   ./scripts/stop.sh --wait 30        # wait up to 30s for the ports to be released
#
# Notes:
#   - Processes are matched by their command line (the four -jar names and chain-worker's
#     server.mjs), never by image name: `pkill -f java` would also kill an IDE run.
#   - scripts/start.sh already stops everything on Ctrl+C (its trap kills the child jobs);
#     this script covers the cases where the terminal was closed, the shell was killed, or
#     you simply want to stop the stack from another terminal.
#   - Ports 8443 / 9441 / 9442 / 9443 / 9545 / 5173 are probed afterwards and reported.
#   - Data is kept: .runtime holds the encrypted H2 database, ledger, chain data and secrets.
set -uo pipefail

JAR_PATTERN='-jar .*(gateway|data-service|audit-service|business-service)-1\.0\.0\.jar'
CHAIN_PATTERN='[n]ode .*server\.mjs'
VITE_PATTERN='[v]ite'
SERVICE_PORTS="8443 9441 9442 9443"
CHAIN_PORT="9545"
VITE_PORT="5173"

KEEP_CHAIN=0
INCLUDE_VITE=0
LIST_ONLY=0
FORCE=0
WAIT_SECONDS=15

usage() {
  sed -n '2,20p' "$0"
}

while [ $# -gt 0 ]; do
  case "$1" in
    --keep-chain|-k)   KEEP_CHAIN=1 ;;
    --include-vite|-v) INCLUDE_VITE=1 ;;
    --list|-l)         LIST_ONLY=1 ;;
    --force|-f)        FORCE=1 ;;
    --wait)            shift; WAIT_SECONDS="${1:-15}" ;;
    -h|--help)         usage; exit 0 ;;
    *) echo "unknown option: $1 (try --help)" >&2; exit 2 ;;
  esac
  shift
done

if ! [[ "$WAIT_SECONDS" =~ ^[0-9]+$ ]]; then
  echo "--wait expects seconds, got: $WAIT_SECONDS" >&2
  exit 2
fi

# ------------------------------------------------------------------ helpers
find_pids() {
  # $1: extended regex matched against the full command line.
  # Excludes this script and its own shell so the pattern can never match itself.
  local pattern="$1" pid out=""
  if command -v pgrep >/dev/null 2>&1; then
    for pid in $(pgrep -f "$pattern" 2>/dev/null || true); do
      [ "$pid" = "$$" ] && continue
      out="$out $pid"
    done
  else
    for pid in $(ps -eo pid=,command= 2>/dev/null | grep -E "$pattern" | grep -v grep | awk '{print $1}'); do
      [ "$pid" = "$$" ] && continue
      out="$out $pid"
    done
  fi
  echo $out
}

cmdline_of() {
  ps -o command= -p "$1" 2>/dev/null | head -n 1
}

short_of() {
  local line
  line="$(cmdline_of "$1")"
  if [ -z "$line" ]; then echo "(gone)"; return; fi
  local jar
  jar="$(echo "$line" | grep -oE '[^/ ]+-1\.0\.0\.jar' | head -n 1)"
  if [ -n "$jar" ]; then echo "$jar"; return; fi
  if echo "$line" | grep -q 'server\.mjs'; then echo "chain-worker (server.mjs)"; return; fi
  if echo "$line" | grep -q 'vite'; then echo "vite dev server"; return; fi
  echo "$(basename "${line%% *}")"
}

port_open() {
  # bash built-in /dev/tcp probe; works without lsof or nc
  (exec 3<>"/dev/tcp/127.0.0.1/$1") >/dev/null 2>&1 && { exec 3>&- 2>/dev/null; return 0; }
  return 1
}

stop_pid() {
  local pid="$1"
  if [ "$FORCE" = "1" ]; then
    kill -9 "$pid" 2>/dev/null || true
  else
    kill -TERM "$pid" 2>/dev/null || true
  fi
}

# ------------------------------------------------------------------ survey
JAVA_PIDS="$(find_pids "$JAR_PATTERN")"
CHAIN_PIDS="$(find_pids "$CHAIN_PATTERN")"
VITE_PIDS="$(find_pids "$VITE_PATTERN")"

echo ""
echo "=== current state ==="
if [ -z "$JAVA_PIDS" ]; then
  echo "  Java services : none"
else
  for pid in $JAVA_PIDS; do echo "  Java service  : PID $pid  $(short_of "$pid")"; done
fi
if [ -z "$CHAIN_PIDS" ]; then
  echo "  chain-worker  : none"
else
  for pid in $CHAIN_PIDS; do echo "  chain-worker  : PID $pid  $(short_of "$pid")"; done
fi
if [ -z "$VITE_PIDS" ]; then
  echo "  Vite dev      : none"
else
  for pid in $VITE_PIDS; do echo "  Vite dev      : PID $pid  $(short_of "$pid")"; done
fi

if [ "$LIST_ONLY" = "1" ]; then
  echo ""
  echo "List only (--list); nothing was stopped."
  exit 0
fi

# ------------------------------------------------------------------ stop
STOPPED=0
echo ""
echo "=== stopping ==="

for pid in $JAVA_PIDS; do
  name="$(short_of "$pid")"
  if stop_pid "$pid"; then
    echo "  stopped java  PID $pid  $name"
    STOPPED=$((STOPPED + 1))
  fi
done

if [ "$KEEP_CHAIN" = "1" ]; then
  [ -n "$CHAIN_PIDS" ] && echo "  chain-worker kept running (--keep-chain)"
else
  for pid in $CHAIN_PIDS; do
    name="$(short_of "$pid")"
    if stop_pid "$pid"; then
      echo "  stopped chain PID $pid  $name"
      STOPPED=$((STOPPED + 1))
    fi
  done
fi

if [ "$INCLUDE_VITE" = "1" ]; then
  for pid in $VITE_PIDS; do
    name="$(short_of "$pid")"
    if stop_pid "$pid"; then
      echo "  stopped vite  PID $pid  $name"
      STOPPED=$((STOPPED + 1))
    fi
  done
elif [ -n "$VITE_PIDS" ]; then
  echo "  Vite dev kept running (pass --include-vite to stop it)"
fi

# ------------------------------------------------------------------ wait & verify
ALL_PORTS="$SERVICE_PORTS $CHAIN_PORT $VITE_PORT"
PENDING="$ALL_PORTS"
deadline=$(( $(date +%s) + WAIT_SECONDS ))
while [ -n "$(echo $PENDING)" ] && [ "$(date +%s)" -lt "$deadline" ]; do
  still=""
  for port in $PENDING; do
    if port_open "$port"; then still="$still $port"; fi
  done
  PENDING="$still"
  [ -z "$(echo $PENDING)" ] && break
  sleep 0.5
done

echo ""
echo "=== ports after stop ==="
for port in $ALL_PORTS; do
  case "$port" in
    8443) label="gateway     " ;;
    9441) label="business    " ;;
    9442) label="data        " ;;
    9443) label="audit       " ;;
    9545) label="chain-worker" ;;
    5173) label="vite dev    " ;;
    *)    label="unknown     " ;;
  esac
  if port_open "$port"; then
    echo "  $port $label  STILL LISTENING"
  else
    echo "  $port $label  released"
  fi
done

echo ""
echo "Stopped $STOPPED process(es)."
echo "Data kept: .runtime (encrypted H2 database, ledger, chain data, secrets)."
echo "Restart with: ./scripts/start.sh"
