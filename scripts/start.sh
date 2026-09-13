#!/usr/bin/env bash
set -euo pipefail

JAVA=java
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

mkdir -p "$ROOT/.logs"

cleanup() {
  echo "停止..."
  kill $(jobs -p) 2>/dev/null || true
  exit 0
}
trap cleanup INT TERM EXIT

echo "==> 启动 chain"
( cd "$ROOT/chain-worker" && node server.mjs ) \
  > "$ROOT/.logs/chain.log" 2>&1 &

# Java 服务
for m in gateway data-service audit-service business-service; do
  echo "==> 启动 $m"
  "$JAVA" -Dfile.encoding=UTF-8 -jar "$ROOT/$m/target/$m-1.0.0.jar" \
    > "$ROOT/.logs/$m.log" 2>&1 &
done

echo "全部启动，日志在 .logs/，Ctrl+C 停止"
wait