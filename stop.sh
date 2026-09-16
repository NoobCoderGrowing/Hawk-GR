#!/bin/bash
# Stop the Hawk-GR backend started by run.sh.
#
# `mvn exec:java` FORKS the app JVM, so a running backend is TWO processes: the
# Maven launcher and the server itself. Killing only the launcher orphans the JVM,
# which keeps serving old code on :8080 — so both are killed here, then the port
# is verified free. (pkill exits 144 on this setup; its status is never trusted,
# pgrep/ss are.)
#
# Usage:
#   ./stop.sh              # stop the backend (waits for :8080 to come free)
#   PORT=9090 ./stop.sh    # if the app was started with a non-default port
#
# The Vite dev server (`cd frontend && npm run dev`) runs in the foreground —
# Ctrl-C stops it, this script does not touch node.

set -u
cd "$(cd "$(dirname "$0")" && pwd)"
PORT="${PORT:-8080}"

# Maven launcher (`... classworlds.launcher.Launcher -q compile exec:java ...`)
MVN_PAT='classworlds\.launcher\.Launcher.*exec:java'
# The forked app JVM (`.../java -cp ... hawk.gr.web.Application`)
APP_PAT='java[^ ]* .*hawk\.gr\.'
# A shell whose command line merely mentions the patterns above is not a target.
SELF_PAT='stop\.sh'

find_pids() {
  { pgrep -f "$MVN_PAT" 2>/dev/null; pgrep -f "$APP_PAT" 2>/dev/null; } |
    sort -u |
    while read -r p; do
      tr '\0' ' ' < "/proc/$p/cmdline" 2>/dev/null | grep -q "$SELF_PAT" && continue
      echo "$p"
    done
}

port_busy() {
  ss -ltn 2>/dev/null | grep -qE "[:.]${PORT}[[:space:]]"
}

PIDS="$(find_pids | tr '\n' ' ')"
PIDS="${PIDS% }"

if [ -z "$PIDS" ] && ! port_busy; then
  echo "-- nothing running on :$PORT — already stopped"
  exit 0
fi

if [ -n "$PIDS" ]; then
  echo "-- stopping: $PIDS"
  for p in $PIDS; do
    echo "   $(tr '\0' ' ' < "/proc/$p/cmdline" 2>/dev/null | cut -c1-100)"
  done
  # shellcheck disable=SC2086
  kill $PIDS 2>/dev/null || true
fi

# Wait for a graceful exit; SIGTERM makes Spring Boot shut down in ~1s.
for _ in $(seq 1 20); do
  [ -z "$(find_pids)" ] && ! port_busy && break
  sleep 0.5
done

if [ -n "$(find_pids)" ]; then
  echo "-- still alive, sending SIGKILL"
  # shellcheck disable=SC2086
  kill -9 $(find_pids) 2>/dev/null || true
  sleep 1
fi

echo "--- result ---"
if [ -n "$(find_pids)" ]; then
  echo "!! processes survived SIGKILL: $(find_pids | tr '\n' ' ')" >&2
  exit 1
fi
if port_busy; then
  echo "!! :$PORT is still listening — held by something else (not a Hawk-GR JVM)" >&2
  exit 1
fi
echo "ok  backend stopped, :$PORT free"
