#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

if [ ! -f .env.docker ]; then
  echo "ERROR: .env.docker not found. Copy .env.docker.example and fill in the values."
  exit 1
fi

podman compose --env-file .env.docker -f docker-compose.yml down 2>/dev/null || true
podman compose --env-file .env.docker -f docker-compose.yml up -d

echo ""
echo "Keycloak starting at http://localhost:8180"
echo "Admin UI:  http://localhost:8180/admin  (check KEYCLOAK_ADMIN* in .env.docker)"
echo "Realm:     http://localhost:8180/realms/mindconnect"
