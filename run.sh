#!/bin/bash
# Run Hawk-GR Java apps. CUDA GPU is auto-detected via OnnxUtils.
#
# Usage:
#   ./run.sh <MainClass>
#
# Examples:
#   ./run.sh hawk.gr.HawkSearch
#   ./run.sh hawk.gr.ItemSidBuilder

set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

MAIN_CLASS="${1:-hawk.gr.HawkSearch}"
shift || true
exec mvn exec:java -Dexec.mainClass="$MAIN_CLASS" -q "$@"
