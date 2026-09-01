#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
runtime_dir="${project_root}/runtime"
rmi_registry_port="${RMI_REGISTRY_PORT:-1199}"
rmi_service_port="${RMI_SERVICE_PORT:-1200}"
web_port="${WEB_PORT:-8443}"
vite_port="${VITE_PORT:-5173}"

for command_name in java mvn node npm openssl curl lsof; do
  if ! command -v "${command_name}" >/dev/null 2>&1; then
    echo "Missing required command: ${command_name}" >&2
    exit 1
  fi
done

java_major="$(java -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')"
node_version="$(node -p 'process.versions.node')"
if [[ "${java_major}" != "17" ]]; then
  echo "Java 17 is required; detected Java ${java_major:-unknown}." >&2
  exit 1
fi
if ! node -e 'const [major, minor] = process.versions.node.split(".").map(Number); process.exit((major === 20 && minor >= 19) || (major > 20 && major < 27) ? 0 : 1)'; then
  echo "Node.js >=20.19 and <27 is required; detected ${node_version}." >&2
  exit 1
fi

for port in "${rmi_registry_port}" "${rmi_service_port}" "${web_port}" "${vite_port}"; do
  if lsof -nP -iTCP:"${port}" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "Port ${port} is already in use. Override the corresponding *_PORT variable." >&2
    exit 1
  fi
done

"${project_root}/scripts/generate-local-secrets.sh"

(cd "${project_root}" && mvn -DskipTests package)
if [[ ! -d "${project_root}/web-frontend/node_modules" ]]; then
  (cd "${project_root}/web-frontend" && npm ci)
fi

tls_password="$(tr -d '\r\n' < "${runtime_dir}/tls-keystore.password")"
grade_db_url="jdbc:h2:file:${runtime_dir}/grade-management;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;AUTO_SERVER=TRUE"
started_pids=()

stop_started_processes() {
  for pid in "${started_pids[@]:-}"; do
    if [[ "${pid}" =~ ^[0-9]+$ ]] && kill -0 "${pid}" >/dev/null 2>&1; then
      kill "${pid}" >/dev/null 2>&1 || true
    fi
  done
}

cleanup_failed_start() {
  local status=$?
  if (( status != 0 )); then
    stop_started_processes
  fi
}

trap cleanup_failed_start EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

(
  cd "${project_root}"
  exec env \
    RMI_REGISTRY_PORT="${rmi_registry_port}" \
    RMI_SERVICE_PORT="${rmi_service_port}" \
    RMI_HMAC_KEY_FILE="${runtime_dir}/rmi-hmac.key" \
    GRADE_DATA_KEY_FILE="${runtime_dir}/grade-data.key" \
    GRADE_LEDGER_FILE="${runtime_dir}/grade-integrity.ledger" \
    GRADE_DB_URL="${grade_db_url}" \
    java -jar "${project_root}/rmi-server/target/rmi-server-1.0.0.jar"
) > "${runtime_dir}/rmi-server.log" 2>&1 &
rmi_pid=$!
started_pids+=("${rmi_pid}")

for _ in {1..60}; do
  if lsof -nP -iTCP:"${rmi_registry_port}" -sTCP:LISTEN >/dev/null 2>&1; then break; fi
  if ! kill -0 "${rmi_pid}" >/dev/null 2>&1; then
    echo "RMI server failed to start. See ${runtime_dir}/rmi-server.log." >&2
    exit 1
  fi
  sleep 1
done
if ! lsof -nP -iTCP:"${rmi_registry_port}" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "Timed out waiting for the RMI registry." >&2
  exit 1
fi

(
  cd "${project_root}"
  exec env \
    SPRING_PROFILES_ACTIVE=local \
    WEB_PORT="${web_port}" \
    TLS_KEYSTORE="file:${runtime_dir}/dev-tls.p12" \
    TLS_KEYSTORE_PASSWORD="${tls_password}" \
    RMI_PORT="${rmi_registry_port}" \
    RMI_HMAC_KEY_FILE="${runtime_dir}/rmi-hmac.key" \
    GRADE_KEY_FILE="${runtime_dir}/grade-data.key" \
    JWT_KEY_FILE="${runtime_dir}/jwt.key" \
    java -jar "${project_root}/web-backend/target/web-backend-1.0.0.jar"
) > "${runtime_dir}/web-backend.log" 2>&1 &
web_pid=$!
started_pids+=("${web_pid}")

for _ in {1..90}; do
  if curl --silent --fail --insecure "https://127.0.0.1:${web_port}/actuator/health" >/dev/null; then break; fi
  if ! kill -0 "${web_pid}" >/dev/null 2>&1; then
    echo "Web backend failed to start. See ${runtime_dir}/web-backend.log." >&2
    exit 1
  fi
  sleep 1
done
if ! curl --silent --fail --insecure "https://127.0.0.1:${web_port}/actuator/health" >/dev/null; then
  echo "Timed out waiting for the HTTPS web backend." >&2
  exit 1
fi

(
  cd "${project_root}/web-frontend"
  exec env \
    VITE_DEV_HTTPS=true \
    VITE_TLS_KEY="${runtime_dir}/dev-tls.key" \
    VITE_TLS_CERT="${runtime_dir}/dev-tls.crt" \
    VITE_API_TARGET="https://127.0.0.1:${web_port}" \
    npm run dev -- --host 127.0.0.1 --port "${vite_port}"
) > "${runtime_dir}/web-frontend.log" 2>&1 &
vite_pid=$!
started_pids+=("${vite_pid}")

for _ in {1..60}; do
  if curl --silent --fail --insecure "https://127.0.0.1:${vite_port}/" >/dev/null; then break; fi
  if ! kill -0 "${vite_pid}" >/dev/null 2>&1; then
    echo "Vue frontend failed to start. See ${runtime_dir}/web-frontend.log." >&2
    exit 1
  fi
  sleep 1
done
if ! curl --silent --fail --insecure "https://127.0.0.1:${vite_port}/" >/dev/null; then
  echo "Timed out waiting for the Vue frontend." >&2
  exit 1
fi

{
  echo "rmi=${rmi_pid}"
  echo "web=${web_pid}"
  echo "vite=${vite_pid}"
} > "${runtime_dir}/local.pids"
chmod 600 "${runtime_dir}/local.pids"
trap - EXIT INT TERM

echo "Secure Grade Management System is running."
echo "Frontend: https://127.0.0.1:${vite_port}"
echo "Backend health: https://127.0.0.1:${web_port}/actuator/health"
echo "Logs: ${runtime_dir}/*.log"
echo "Use scripts/stop-local.sh to stop all three processes."
