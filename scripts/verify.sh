#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
(cd "${project_root}" && mvn test)
(cd "${project_root}/web-frontend" && npm test && npm run build)

