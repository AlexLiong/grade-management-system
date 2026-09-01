#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
pid_file="${project_root}/runtime/local.pids"

if [[ ! -f "${pid_file}" ]]; then
  echo "No local process file found; nothing to stop."
  exit 0
fi

while IFS='=' read -r name pid; do
  if [[ ! "${name}" =~ ^(rmi|web|vite)$ || ! "${pid}" =~ ^[0-9]+$ ]]; then
    echo "Ignoring invalid process entry: ${name}=${pid}" >&2
    continue
  fi
  if kill -0 "${pid}" >/dev/null 2>&1; then
    kill "${pid}"
    echo "Stopped ${name} process ${pid}."
  fi
done < "${pid_file}"

: > "${pid_file}"

