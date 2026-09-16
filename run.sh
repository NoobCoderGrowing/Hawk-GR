#!/bin/bash
# Run Hawk-GR Java apps. CUDA GPU is auto-detected via OnnxUtils.
#
# Runtime assets (BART weights + T index) are gitignored — see README §4. If any
# of them is missing, they are fetched from GitHub Releases via
# scripts/fetch_assets.sh before the app starts (resumable + sha256 verified).
#
# Usage:
#   ./run.sh <MainClass>
#
# Examples:
#   ./run.sh hawk.gr.web.Application     # REST API + web UI
#   ./run.sh hawk.gr.HawkSearch          # interactive CLI search
#   ./run.sh hawk.gr.ItemSidBuilder      # rebuild items_with_sid.json + T index
#
# Env:
#   BART_MODEL_DIR=<dir>       weights dir (default model/); auto-fetch only applies
#                              to the default, point it elsewhere and manage it yourself
#   HAWK_GR_SKIP_ASSETS=1      skip the asset check entirely (offline / index rebuilds)
#   HAWK_GR_TAG=<tag>          release tag to download from (default assets-v1)

set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

MAIN_CLASS="${1:-hawk.gr.HawkSearch}"
shift || true

# ---- check runtime assets ----------------------------------------------------
MODEL_DIR="${BART_MODEL_DIR:-model}"
NEED=()

# Weights: only auto-manage the default model/ dir. A custom BART_MODEL_DIR is
# the user's own business (e.g. an experiment checkout) — never write into it.
if [ "$MODEL_DIR" = "model" ]; then
  for f in tokenizer.json bart_encoder.onnx bart_decoder.onnx; do
    [ -f "model/$f" ] || NEED+=("model/$f")
  done
fi

for f in src/main/resources/sid_to_items.json src/main/resources/items_with_sid.json; do
  [ -f "$f" ] || NEED+=("$f")
done

if [ "${#NEED[@]}" -gt 0 ]; then
  if [ -n "${HAWK_GR_SKIP_ASSETS:-}" ]; then
    echo "!! missing ${#NEED[@]} runtime asset(s), HAWK_GR_SKIP_ASSETS=1 → not fetching:"
  else
    echo "!! missing ${#NEED[@]} runtime asset(s), fetching from GitHub Releases:"
  fi
  printf '   - %s\n' "${NEED[@]}"

  if [ -z "${HAWK_GR_SKIP_ASSETS:-}" ]; then
    if ! scripts/fetch_assets.sh; then
      echo "!! fetch failed (offline? release not published?) — see README §4" >&2
    fi
    # Fail loudly here rather than letting the app die later on a missing file.
    for f in "${NEED[@]}"; do
      [ -f "$f" ] || { echo "!! still missing: $f — cannot start" >&2; exit 1; }
    done
    echo "-- assets ready"
  fi
fi

exec mvn exec:java -Dexec.mainClass="$MAIN_CLASS" -q "$@"
