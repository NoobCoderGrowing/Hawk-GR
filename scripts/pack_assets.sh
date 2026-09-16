#!/bin/bash
# Pack the runtime assets (BART weights + T index) into release tarballs.
#
# These files are gitignored (a single .onnx is >100 MB, which GitHub rejects),
# so they are distributed as GitHub Release assets and fetched by
# scripts/fetch_assets.sh.
#
# Usage:
#   scripts/pack_assets.sh [outdir]     # default outdir: dist/
#
# Produces (gzip is used because it needs no extra tooling on the download side):
#   hawk-gr-model-stage3.tar.gz   model/{tokenizer.json,bart_encoder.onnx,bart_decoder.onnx}
#   hawk-gr-index.tar.gz          src/main/resources/{sid_to_items.json,items_with_sid.json}
#   SHA256SUMS                    checksums for the tarballs above
#
# Then upload the tarballs + SHA256SUMS to a GitHub Release, and commit the
# refreshed scripts/assets.sha256 so fetch_assets.sh can verify downloads.

set -e
cd "$(cd "$(dirname "$0")/.." && pwd)"

OUT="${1:-dist}"
mkdir -p "$OUT"

# Tarballs produced by this run — checksummed below. Tracking them explicitly
# keeps a stale tarball from an earlier run out of SHA256SUMS.
PRODUCED=""

# name <relative paths...>
pack() {
  local name="$1"; shift
  local missing=0
  for p in "$@"; do
    if [ ! -f "$p" ]; then
      echo "!! missing: $p" >&2
      missing=1
    fi
  done
  [ "$missing" -eq 0 ] || { echo "   skip $name" >&2; return 1; }

  echo "==> $name.tar.gz"
  tar -czf "$OUT/$name.tar.gz" "$@"
  PRODUCED="$PRODUCED $name.tar.gz"
}

pack hawk-gr-model-stage3 model/tokenizer.json model/bart_encoder.onnx model/bart_decoder.onnx || true
pack hawk-gr-index        src/main/resources/sid_to_items.json src/main/resources/items_with_sid.json || true

cd "$OUT"
sha256sum $PRODUCED > SHA256SUMS
cd - >/dev/null

# Keep the checksum list in the repo: fetch_assets.sh verifies against it, so a
# release can't be swapped under an existing tag without the diff showing up.
cp "$OUT/SHA256SUMS" scripts/assets.sha256

echo
echo "--- $OUT ---"
du -h "$OUT"/hawk-gr-*.tar.gz
echo
echo "Next: create a release (tag e.g. assets-v1) and upload $OUT/*.tar.gz + $OUT/SHA256SUMS"
echo "      then commit scripts/assets.sha256"
