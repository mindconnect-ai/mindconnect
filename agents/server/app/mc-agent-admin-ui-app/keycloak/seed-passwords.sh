#!/bin/bash
# Waits for Keycloak to be ready, then sets seed user passwords from environment variables.
# Runs once as an init container or sidecar after keycloak starts.

set -e

KC_URL="http://keycloak:8080"
REALM="mindconnect"

echo "Waiting for Keycloak at $KC_URL ..."
until curl -sf "$KC_URL/realms/master" > /dev/null; do
  sleep 3
done
echo "Keycloak is up."

# Obtain admin token
TOKEN=$(curl -sf "$KC_URL/realms/master/protocol/openid-connect/token" \
  -d "client_id=admin-cli" \
  -d "username=$KEYCLOAK_ADMIN" \
  -d "password=$KEYCLOAK_ADMIN_PASSWORD" \
  -d "grant_type=password" | grep -o '"access_token":"[^"]*' | cut -d'"' -f4)

if [ -z "$TOKEN" ]; then
  echo "ERROR: Could not obtain admin token" >&2
  exit 1
fi

set_password() {
  local USERNAME=$1
  local PASSWORD=$2

  USER_ID=$(curl -sf "$KC_URL/admin/realms/$REALM/users?username=$USERNAME" \
    -H "Authorization: Bearer $TOKEN" | grep -o '"id":"[^"]*' | head -1 | cut -d'"' -f4)

  if [ -z "$USER_ID" ]; then
    echo "WARNING: User $USERNAME not found, skipping." >&2
    return
  fi

  curl -sf -X PUT "$KC_URL/admin/realms/$REALM/users/$USER_ID/reset-password" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"type\":\"password\",\"value\":\"$PASSWORD\",\"temporary\":false}"

  echo "Password set for $USERNAME."
}

set_password "mc_user"  "$KC_PASSWORD_MC_USER"
set_password "mc_admin" "$KC_PASSWORD_MC_ADMIN"
set_password "mc_hr"    "$KC_PASSWORD_MC_HR"
set_password "mc_dev"   "$KC_PASSWORD_MC_DEV"

echo "All seed user passwords set."
