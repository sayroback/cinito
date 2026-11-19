#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CAST_PORT="${1:-8765}"
HOST_IP="${2:-}"

if [[ -z "${HOST_IP}" ]]; then
  HOST_IP="$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for (i=1;i<=NF;i++) if ($i=="src") {print $(i+1); exit}}')"
fi
if [[ -z "${HOST_IP}" ]]; then
  echo "ERROR: Could not detect host LAN IPv4. Pass it explicitly: $0 <port> <host_ip>" >&2
  exit 1
fi

EMU_SERIAL="${ANDROID_SERIAL:-$(adb devices | awk 'NR>1 && $2=="device" && $1 ~ /^emulator-/ {print $1; exit}')}"
if [[ -z "${EMU_SERIAL}" ]]; then
  echo "ERROR: No running Android emulator found in 'adb devices'." >&2
  exit 1
fi

echo "Using emulator=${EMU_SERIAL} host_ip=${HOST_IP} port=${CAST_PORT}"

adb -s "${EMU_SERIAL}" shell setprop debug.cinito.cast_host "${HOST_IP}"
adb -s "${EMU_SERIAL}" shell setprop debug.cinito.cast_port "${CAST_PORT}"
adb -s "${EMU_SERIAL}" forward "tcp:${CAST_PORT}" "tcp:${CAST_PORT}"

python3 "${SCRIPT_DIR}/cast_relay.py" "${HOST_IP}" "${CAST_PORT}" &
RELAY_PID=$!

cleanup() {
  echo ""
  echo "Stopping relay (pid=${RELAY_PID})..."
  kill "${RELAY_PID}" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

adb -s "${EMU_SERIAL}" logcat -c
adb -s "${EMU_SERIAL}" shell am start -n org.cinito.online/.MainActivity >/dev/null || true

echo "Streaming CineCast / AudioProbe / webtor-engine logs (Ctrl+C to stop)..."
adb -s "${EMU_SERIAL}" logcat -s CineCast:* AudioProbe:* webtor-engine:* AndroidRuntime:E
