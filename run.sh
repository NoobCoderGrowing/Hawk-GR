#!/bin/bash
# Run Hawk-GR Java apps. CUDA GPU is auto-detected via OnnxUtils.
# Compiles first (mvn -q compile), so Java edits and a rebuilt frontend are picked up.
#
# Runtime assets (BART weights + T index) are gitignored — see README §5. If any
# of them is missing, they are fetched from GitHub Releases via
# scripts/fetch_assets.sh before the app starts (resumable + sha256 verified).
#
# Usage:
#   ./run.sh [MainClass]                 # default: hawk.gr.web.Application
#
# Examples:
#   ./run.sh                             # REST API + web UI on :8080
#   ./run.sh hawk.gr.HawkSearch          # interactive CLI search
#   ./run.sh hawk.gr.ItemSidBuilder      # rebuild items_with_sid.json + T index
#
# Before starting, it also: fetches missing weights/index (README §5), and builds
# the UI when src/main/resources/static/index.html is absent.
#
# Env:
#   BART_MODEL_DIR=<dir>       weights dir (default model/); auto-fetch only applies
#                              to the default, point it elsewhere and manage it yourself
#   HAWK_GR_SKIP_ASSETS=1      skip the asset check entirely (offline / index rebuilds)
#   HAWK_GR_SKIP_FRONTEND=1    skip the UI build check (backend/API only)
#   HAWK_GR_TAG=<tag>          release tag to download from (default assets-v1)

set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

MAIN_CLASS="${1:-hawk.gr.web.Application}"
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
      echo "!! fetch failed (offline? release not published?) — see README §5" >&2
    fi
    # Fail loudly here rather than letting the app die later on a missing file.
    for f in "${NEED[@]}"; do
      [ -f "$f" ] || { echo "!! still missing: $f — cannot start" >&2; exit 1; }
    done
    echo "-- assets ready"
  fi
fi

# ---- ensure a frontend build exists ------------------------------------------
# The UI is a separate Vite app; its output lands in src/main/resources/static
# (gitignored) and Spring Boot serves it on :8080. Build it here so a fresh clone
# is usable with a single command. An existing build is left alone — re-run
# `npm run build` in frontend/ after editing the UI, or delete static/index.html.
WEBAPP="src/main/resources/static/index.html"
if [ ! -f "$WEBAPP" ] && [ -z "${HAWK_GR_SKIP_FRONTEND:-}" ]; then
  echo "-- frontend build missing ($WEBAPP), building"
  if ! command -v npm >/dev/null 2>&1; then
    echo "!! npm not found — skipping the UI build; :8080 will serve no page" >&2
  else
    if [ ! -d frontend/node_modules ]; then
      echo "   installing frontend deps (~42 MB, first time only)"
      if [ -f frontend/package-lock.json ]; then
        (cd frontend && npm ci)   || echo "!! npm ci failed" >&2
      else
        (cd frontend && npm install) || echo "!! npm install failed" >&2
      fi
    fi
    if (cd frontend && npm run build); then
      echo "-- frontend built → served by Spring Boot on :8080"
    else
      echo "!! frontend build failed — :8080 will have no page (the API still works)" >&2
    fi
  fi
fi

# exec:java runs the plugin goal directly and does not walk the lifecycle, so
# compile explicitly first: a fresh clone has no target/classes, and otherwise
# changed Java — or a rebuilt frontend (static/ → target/classes/static) — would
# silently run stale.
exec mvn -q compile exec:java -Dexec.mainClass="$MAIN_CLASS" "$@"
