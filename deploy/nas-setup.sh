#!/usr/bin/env bash
# One-time NAS setup for the backup pull and the health check (run ON THE NAS as admin):
#   bash nas-setup.sh
# Creates an ssh key for the VPS, prints the public key to add to the VPS root authorized_keys, installs the
# two scripts next to this one and the crontab lines. Idempotent.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
KEY=$HOME/.ssh/chatter_vps
mkdir -p "$HOME/.ssh" && chmod 700 "$HOME/.ssh"
if [ ! -f "$KEY" ]; then
  ssh-keygen -t ed25519 -N "" -C "nas-chatter-$(hostname)" -f "$KEY" >/dev/null
  echo "== new key; add this line to /root/.ssh/authorized_keys on the VPS:"
fi
echo "restrict,command=\"/usr/local/bin/chatter-nas-cmd\" $(cat "$KEY.pub")"
chmod +x "$HERE/nas-backup-pull.sh" "$HERE/healthcheck.sh"
( crontab -l 2>/dev/null | grep -v -E 'nas-backup-pull.sh|healthcheck.sh' ;
  echo "17 4 * * * $HERE/nas-backup-pull.sh >> $HERE/backup.log 2>&1" ;
  echo "*/5 * * * * $HERE/healthcheck.sh >> $HERE/health.log 2>&1" ) | crontab -
echo "== crontab:"; crontab -l | grep -E 'nas-backup-pull|healthcheck'
