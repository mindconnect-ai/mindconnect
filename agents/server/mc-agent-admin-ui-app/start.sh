#!/usr/bin/env bash
# Starts the agent admin UI via `mvn spring-boot:run`, with the monorepo's
# mc.env loaded into the environment first (API keys, model config, …).
#
# Runs from the repo root so relative paths (data/, logs/, data/workflows)
# resolve the same way they do in development.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
ENV_FILE="$REPO_ROOT/mc.env"

if [[ -f "$ENV_FILE" ]]; then
    echo "Loading environment from $ENV_FILE"
    # Parse line by line instead of sourcing: mc.env is a docker-style env
    # file, so values may contain unquoted spaces (e.g. KC_SCOPE=openid
    # profile email) that bash `source` would try to execute as commands.
    while IFS= read -r line || [[ -n "$line" ]]; do
        [[ "$line" =~ ^[[:space:]]*(#|$) ]] && continue
        if [[ "$line" =~ ^[A-Za-z_][A-Za-z0-9_]*= ]]; then
            key="${line%%=*}"
            value="${line#*=}"
            # Strip one pair of surrounding quotes, if present.
            if [[ "$value" == \"*\" && "$value" == *\" ]]; then value="${value%\"}"; value="${value#\"}"; fi
            if [[ "$value" == \'*\' && "$value" == *\' ]]; then value="${value%\'}"; value="${value#\'}"; fi
            export "$key=$value"
        else
            echo "WARN: skipping malformed line in mc.env: ${line%% *}" >&2
        fi
    done < "$ENV_FILE"
else
    echo "WARN: $ENV_FILE not found — starting without it" >&2
fi

cd "$REPO_ROOT"
# spring-boot:run forks the app with the PROJECT basedir as its working
# directory, so the `cd` above is not enough — without this the app would read
# and write agents/server/mc-agent-admin-ui-app/data instead of the repo's.
exec mvn -f "$SCRIPT_DIR/pom.xml" spring-boot:run \
    -Dspring-boot.run.workingDirectory="$REPO_ROOT" "$@"
