#!/bin/bash
# Fetch the runtime assets (BART weights + T index) from GitHub Releases.
#
# These files are gitignored: a single .onnx is >100 MB, which GitHub rejects
# outright, and shipping them in git would make every clone pay for them.
#
# Usage:
#   scripts/fetch_assets.sh [component ...]      # default: model index
#     model    model/{tokenizer.json,bart_encoder.onnx,bart_decoder.onnx}
#     index    src/main/resources/{sid_to_items.json,items_with_sid.json}
#     all      both of the above
#
# Env overrides:
#   HAWK_GR_TAG       release tag            (default assets-v1)
#   HAWK_GR_REPO      owner/repo             (default NoobCoderGrowing/Hawk-GR)
#   HAWK_GR_BASE_URL  full asset base URL    (default releases/download/<tag>)
#   HAWK_GR_CACHE     download cache dir     (default .cache/assets)
#   FORCE=1           re-extract even when the target files are already present
#
# Downloads resume (curl -C -) and are verified against scripts/assets.sha256
# when that file is present.

set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

REPO="${HAWK_GR_REPO:-NoobCoderGrowing/Hawk-GR}"
TAG="${HAWK_GR_TAG:-assets-v1}"
BASE="${HAWK_GR_BASE_URL:-https://github.com/$REPO/releases/download/$TAG}"
CACHE="${HAWK_GR_CACHE:-$ROOT/.cache/assets}"

# tarball name -> files it must place under the repo root
members() {
  case "$1" in
    hawk-gr-model-stage3) echo "model/tokenizer.json model/bart_encoder.onnx model/bart_decoder.onnx" ;;
    hawk-gr-index)        echo "src/main/resources/sid_to_items.json src/main/resources/items_with_sid.json" ;;
  esac
}

want=""
for c in "${@:-}"; do
  case "$c" in
    model) want="$want hawk-gr-model-stage3" ;;
    index) want="$want hawk-gr-index" ;;
    all)   want="$want hawk-gr-model-stage3 hawk-gr-index" ;;
    "")    ;;
    *)     echo "unknown component: $c" >&2; exit 2 ;;
  esac
done
[ -n "${*:-}" ] || want="hawk-gr-model-stage3 hawk-gr-index"

mkdir -p "$CACHE"

verify() {  # verify <tarball> ; non-zero if checksum mismatch
  local tb="$1" name want_sum got
  name="$(basename "$tb")"
  [ -f scripts/assets.sha256 ] || { echo "   (no scripts/assets.sha256 — skipping checksum)"; return 0; }
  want_sum="$(awk -v n="$name" '$2 == n {print $1}' scripts/assets.sha256)"
  [ -n "$want_sum" ] || { echo "   (no checksum entry for $name — skipping)"; return 0; }
  got="$(sha256sum "$tb" | cut -d' ' -f1)"
  if [ "$want_sum" = "$got" ]; then
    echo "   sha256 ok"
    return 0
  fi
  echo "!! sha256 mismatch for $name" >&2
  echo "   expected $want_sum" >&2
  echo "   actual   $got" >&2
  return 1
}

for name in $want; do
  files="$(members "$name")"
  [ -n "$files" ] || continue

  # Already extracted? Then there is nothing to download.
  complete=1
  for f in $files; do
    [ -f "$f" ] || complete=0
  done
  if [ "$complete" -eq 1 ] && [ -z "${FORCE:-}" ]; then
    echo "== $name: already in place, skipping"
    continue
  fi

  tarball="$CACHE/$name.tar.gz"
  if [ -f "$tarball" ] && verify "$tarball"; then
    echo "== $name: using cached $tarball"
  else
    echo "== $name: downloading $BASE/$name.tar.gz"
    rm -f "$tarball" "$tarball.part"
    if ! curl -fL --retry 3 --retry-delay 2 --connect-timeout 15 \
         -C - -o "$tarball.part" "$BASE/$name.tar.gz"; then
      echo "   resume failed, restarting download"
      rm -f "$tarball.part"
      curl -fL --retry 3 --retry-delay 2 --connect-timeout 15 \
        -o "$tarball.part" "$BASE/$name.tar.gz"
    fi
    mv "$tarball.part" "$tarball"
    verify "$tarball"
  fi

  echo "   extracting"
  tar -xzf "$tarball" -C "$ROOT"
done

echo
echo "--- asset status ---"
for name in hawk-gr-model-stage3 hawk-gr-index; do
  for f in $(members "$name"); do
    if [ -f "$f" ]; then
      printf '  ok      %s (%s)\n' "$f" "$(du -h "$f" | cut -f1)"
    else
      printf '  missing %s\n' "$f"
    fi
  done
done
echo
echo "Next: mvn -q compile && ./run.sh hawk.gr.web.Application"
