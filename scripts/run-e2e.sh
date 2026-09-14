#!/usr/bin/env bash
set -euo pipefail

readonly E2E_COMPOSE_PROJECT="spotyourslot-e2e"
readonly REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly DEFAULT_SLOW_MO_MS=800

headed=false
slow_mode=false
slow_mo_ms=0

usage() {
  echo "Usage: $0 [--headed] [--slow|--slow=<milliseconds>]" >&2
}

for argument in "$@"; do
  case "${argument}" in
    --headed)
      headed=true
      ;;
    --slow)
      slow_mode=true
      slow_mo_ms="${DEFAULT_SLOW_MO_MS}"
      ;;
    --slow=*)
      slow_mode=true
      slow_mo_ms="${argument#--slow=}"
      if [[ ! "${slow_mo_ms}" =~ ^0*([0-9]{1,5})$ ]] ||
        (( 10#${BASH_REMATCH[1]} > 10000 )); then
        echo "Slow motion must be an integer between 0 and 10000 milliseconds." >&2
        usage
        exit 1
      fi
      ;;
    *)
      echo "Unknown argument: ${argument}" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ "${slow_mode}" == true && "${headed}" != true ]]; then
  echo "Slow mode requires --headed." >&2
  usage
  exit 1
fi

playwright_arguments=()
if [[ "${headed}" == true ]]; then
  playwright_arguments+=(--headed)
fi

export E2E_POSTGRES_PORT="${E2E_POSTGRES_PORT:-55432}"
export E2E_BACKEND_PORT="${E2E_BACKEND_PORT:-18080}"
export E2E_FRONTEND_PORT="${E2E_FRONTEND_PORT:-15173}"
export E2E_POSTGRES_DB="spotyourslot_e2e"
export E2E_POSTGRES_USER="spotyourslot_e2e"
export E2E_POSTGRES_PASSWORD="${E2E_POSTGRES_PASSWORD:-$(openssl rand -hex 24)}"
export E2E_ADMIN_EMAIL="platform-admin-e2e@example.invalid"
export E2E_ADMIN_DISPLAY_NAME="E2E Platform Administrator"
export E2E_ADMIN_PASSWORD="${E2E_ADMIN_PASSWORD:-$(openssl rand -hex 24)}"
export E2E_OWNER_PASSWORD="${E2E_OWNER_PASSWORD:-$(openssl rand -hex 24)}"
export PLAYWRIGHT_NO_COPY_PROMPT=1

validate_isolation() {
  if [[ "${E2E_COMPOSE_PROJECT}" != "spotyourslot-e2e" ]]; then
    echo "Refusing to operate on an unexpected Compose project." >&2
    exit 1
  fi
  for port in "${E2E_POSTGRES_PORT}" "${E2E_BACKEND_PORT}" "${E2E_FRONTEND_PORT}"; do
    if [[ ! "${port}" =~ ^[0-9]+$ ]] || (( port < 1024 || port > 65535 )); then
      echo "E2E ports must be numeric values between 1024 and 65535." >&2
      exit 1
    fi
  done
  if [[ "${E2E_POSTGRES_PORT}" == 5432 || "${E2E_BACKEND_PORT}" == 8080 ||
        "${E2E_FRONTEND_PORT}" == 5173 ]]; then
    echo "Refusing to use a normal development port for E2E." >&2
    exit 1
  fi
  if [[ "${E2E_POSTGRES_PORT}" == "${E2E_BACKEND_PORT}" ||
        "${E2E_POSTGRES_PORT}" == "${E2E_FRONTEND_PORT}" ||
        "${E2E_BACKEND_PORT}" == "${E2E_FRONTEND_PORT}" ]]; then
    echo "E2E ports must be distinct." >&2
    exit 1
  fi
}

compose_e2e() {
  docker compose \
    --project-name "${E2E_COMPOSE_PROJECT}" \
    --file "${REPOSITORY_ROOT}/compose.yaml" \
    "$@"
}

cleanup() {
  local exit_status=$?
  validate_isolation
  if ! compose_e2e down --volumes --remove-orphans >/dev/null; then
    echo "Failed to remove the disposable E2E Compose project." >&2
    exit_status=1
  fi
  trap - EXIT
  exit "${exit_status}"
}

validate_isolation
trap cleanup EXIT
trap 'exit 130' INT TERM

export POSTGRES_PORT="${E2E_POSTGRES_PORT}"
export POSTGRES_DB="${E2E_POSTGRES_DB}"
export POSTGRES_USER="${E2E_POSTGRES_USER}"
export POSTGRES_PASSWORD="${E2E_POSTGRES_PASSWORD}"

# Remove only the disposable E2E project so Flyway always starts from an empty database.
validate_isolation
compose_e2e down --volumes --remove-orphans >/dev/null
compose_e2e up --detach --wait postgres

cd "${REPOSITORY_ROOT}/frontend"
PLAYWRIGHT_SLOW_MO="${slow_mo_ms}" \
  ./node_modules/.bin/playwright test "${playwright_arguments[@]}"
