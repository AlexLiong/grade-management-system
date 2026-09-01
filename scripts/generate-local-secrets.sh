#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
runtime_dir="${project_root}/runtime"
umask 077
mkdir -p "${runtime_dir}"

generate_secret() {
  local target="$1"
  if [[ ! -s "${target}" ]]; then
    openssl rand -base64 32 > "${target}"
    chmod 600 "${target}"
  fi
}

generate_secret "${runtime_dir}/rmi-hmac.key"
generate_secret "${runtime_dir}/grade-data.key"
generate_secret "${runtime_dir}/jwt.key"
generate_secret "${runtime_dir}/tls-keystore.password"

tls_key="${runtime_dir}/dev-tls.key"
tls_cert="${runtime_dir}/dev-tls.crt"
tls_store="${runtime_dir}/dev-tls.p12"

if [[ ! -s "${tls_key}" || ! -s "${tls_cert}" ]]; then
  openssl req -x509 -newkey rsa:3072 -sha256 -days 825 -nodes \
    -keyout "${tls_key}" -out "${tls_cert}" \
    -subj "/C=CN/ST=Shaanxi/L=Xi'an/O=Coursework/OU=Development/CN=localhost" \
    -addext "subjectAltName=DNS:localhost,IP:127.0.0.1" >/dev/null 2>&1
  chmod 600 "${tls_key}" "${tls_cert}"
fi

if [[ ! -s "${tls_store}" ]]; then
  tls_password="$(tr -d '\r\n' < "${runtime_dir}/tls-keystore.password")"
  openssl pkcs12 -export -name secure-grade-local \
    -inkey "${tls_key}" -in "${tls_cert}" -out "${tls_store}" \
    -passout "pass:${tls_password}" >/dev/null 2>&1
  chmod 600 "${tls_store}"
fi

echo "Local runtime secrets and development TLS certificate are ready in ${runtime_dir}."

