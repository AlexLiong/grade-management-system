#!/usr/bin/env bash
# Start the full local stack on macOS / Linux.
#
# Secrets (service HMAC keys, TLS password, H2 whole-database encryption password) live in
# .runtime/secrets.json and are injected here BOTH as environment variables and as -D startup
# properties. Generate them first with: node scripts/setup.mjs
set -euo pipefail

JAVA=java
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNTIME="$ROOT/.runtime"
SECRETS="$RUNTIME/secrets.json"

mkdir -p "$ROOT/.logs"

if [ ! -f "$SECRETS" ]; then
  echo "==> $SECRETS not found, generating keys and certificates"
  node "$ROOT/scripts/setup.mjs"
fi

# Load secrets into the environment (one KEY=value per line, no eval).
while IFS='=' read -r name value; do
  [ -n "$name" ] || continue
  export "$name=$value"
done < <(node -e '
const fs = require("fs");
const s = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
for (const [k, v] of Object.entries(s)) console.log(k + "=" + v);
' "$SECRETS")

: "${DB_CIPHER_KEY:?missing DB_CIPHER_KEY in secrets.json}"
: "${DB_PASSWORD:?missing DB_PASSWORD in secrets.json}"
: "${TLS_PASSWORD:?missing TLS_PASSWORD in secrets.json}"

# H2 整库加密的会话口令是「文件口令 + 空格 + 用户口令」
export DB_PASSWORD="$DB_CIPHER_KEY $DB_PASSWORD"

JVM_OPTS=(
  "-Dfile.encoding=UTF-8"
  "-Dcampus.runtime=$RUNTIME"
  "-Djavax.net.ssl.trustStore=$RUNTIME/truststore.p12"
  "-Djavax.net.ssl.trustStorePassword=$TLS_PASSWORD"
  "-DGATEWAY_KEY=$GATEWAY_KEY"
  "-DBUSINESS_KEY=$BUSINESS_KEY"
  "-DDATA_KEY=$DATA_KEY"
  "-DAUDIT_KEY=$AUDIT_KEY"
  "-DLEDGER_KEY=$LEDGER_KEY"
  "-DAUDIT_DATA_KEY=$AUDIT_DATA_KEY"
  "-DTLS_PASSWORD=$TLS_PASSWORD"
  "-DDB_PASSWORD=$DB_PASSWORD"
  "-DDB_CIPHER_KEY=$DB_CIPHER_KEY"
)
# 数据库文件口令也以环境变量形式保留一份，方便排查
export DB_CIPHER_KEY="$DB_CIPHER_KEY"

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
  "$JAVA" "${JVM_OPTS[@]}" -jar "$ROOT/$m/target/$m-1.0.0.jar" \
    > "$ROOT/.logs/$m.log" 2>&1 &
done

echo "全部启动，日志在 .logs/，Ctrl+C 停止"
wait
