#!/usr/bin/env bash
# Fix podman machine VM clock drift after host suspend.
#
# The podman machine VM clock falls behind whenever the Mac sleeps. Keycloak
# then signs JWTs with an `exp` in the past, and OIDC login fails with
# "Jwt expired at ...". Default chrony config (`makestep 1.0 3`) only steps
# the clock during the first 3 NTP updates after boot, then refuses large
# corrections — so a long suspend leaves the VM permanently behind.
#
# This patches chrony to `makestep 1.0 -1` (step at any time, unlimited),
# restarts chronyd, and forces an immediate resync. Run once per podman
# machine; the change persists until `podman machine reset`.

set -euo pipefail

if ! command -v podman >/dev/null 2>&1; then
  echo "podman not found on PATH" >&2
  exit 1
fi

if ! podman machine ssh true >/dev/null 2>&1; then
  echo "podman machine is not running. Start it with: podman machine start" >&2
  exit 1
fi

echo "Patching /etc/chrony.conf in podman VM..."
podman machine ssh "
  if grep -q '^makestep 1.0 -1' /etc/chrony.conf; then
    echo '  already patched'
  else
    sudo sed -i 's/^makestep 1.0 3\$/makestep 1.0 -1/' /etc/chrony.conf
    echo '  patched'
  fi
"

echo "Restarting chronyd..."
podman machine ssh "sudo systemctl restart chronyd"

echo "Waiting for NTP sources..."
sleep 3

echo "Forcing immediate clock step..."
podman machine ssh "sudo chronyc -a makestep" >/dev/null

echo ""
echo "host:      $(date -u)"
echo "podman VM: $(podman machine ssh date -u)"
echo ""
echo "Done. Clock drift will now self-heal within ~64s after future host suspends."
