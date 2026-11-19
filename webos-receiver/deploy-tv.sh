#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEVICE="${1:-lgtv}"
OUT_DIR="$(mktemp -d)"
trap 'rm -rf "$OUT_DIR"' EXIT

ares-package -o "$OUT_DIR" "$SCRIPT_DIR"
IPK_FILE="$(find "$OUT_DIR" -maxdepth 1 -name '*.ipk' | head -n 1)"
if [[ -z "$IPK_FILE" ]]; then
  echo "No se generó el paquete .ipk" >&2
  exit 1
fi

echo "Instalando $IPK_FILE en dispositivo '$DEVICE'..."
ares-install -d "$DEVICE" "$IPK_FILE"
echo "Listo: org.cinito.tv instalado en '$DEVICE'."
