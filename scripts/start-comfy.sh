#!/bin/sh
# Read existing ComfyUI code/models; direct runtime outputs into this project.
set -eu
cd "$(dirname "$0")/.."
COMFY_SOURCE=${1:?Usage: scripts/start-comfy.sh /absolute/path/to/ComfyUI /absolute/path/to/models}
COMFY_MODELS=${2:?Provide the existing models directory}
COMFY_PYTHON=${COMFY_PYTHON:-"$COMFY_SOURCE/.venv/bin/python"}
[ -f "$COMFY_SOURCE/main.py" ]
[ -d "$COMFY_MODELS" ]
mkdir -p .runtime/comfy .runtime/cache .runtime/tmp
export PYTHONDONTWRITEBYTECODE=1
export XDG_CACHE_HOME="$PWD/.runtime/cache"
export TMPDIR="$PWD/.runtime/tmp"
export HF_HOME="$PWD/.runtime/cache/huggingface"
export TORCH_HOME="$PWD/.runtime/cache/torch"
exec "$COMFY_PYTHON" -B "$COMFY_SOURCE/main.py" \
  --base-directory "$PWD/.runtime/comfy" --models-directory "$COMFY_MODELS" \
  --disable-all-custom-nodes --disable-auto-launch --offline --listen 127.0.0.1 --port 8188
