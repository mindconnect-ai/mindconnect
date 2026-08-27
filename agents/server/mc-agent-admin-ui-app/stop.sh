#!/usr/bin/env bash
# Stops the agent admin UI: kills whatever is listening on the app port
# (default 9090, override with the first argument). Tries SIGTERM first,
# escalates to SIGKILL if the process hasn't gone after ~10 seconds.
set -euo pipefail

PORT="${1:-9090}"

PIDS="$(lsof -ti tcp:"$PORT" || true)"
if [[ -z "$PIDS" ]]; then
    echo "Nothing listening on port $PORT."
    exit 0
fi

echo "Stopping process(es) on port $PORT: $PIDS"
# shellcheck disable=SC2086
kill $PIDS

for _ in $(seq 1 20); do
    sleep 0.5
    if ! lsof -ti tcp:"$PORT" >/dev/null 2>&1; then
        echo "Stopped."
        exit 0
    fi
done

echo "Still running after 10s — sending SIGKILL"
# shellcheck disable=SC2046
kill -9 $(lsof -ti tcp:"$PORT") 2>/dev/null || true
echo "Stopped (forced)."
