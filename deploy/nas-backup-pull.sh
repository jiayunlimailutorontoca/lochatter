#!/usr/bin/env bash
# Runs on another machine (cron): pulls last night's server backup so the chat survives a dead disk.
#   crontab: 17 4 * * * /path/to/nas-backup-pull.sh >> /path/to/backup.log 2>&1
# Host and port come from deploy/local.env next to this script, or /etc/chatter/deploy.env.
# Needs: ssh key at ~/.ssh/chatter_vps (installed by deploy/nas-setup.sh), rsync if you have it.
set -euo pipefail
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
if [ -f "$SCRIPT_DIR/local.env" ]; then
  set -a
  # shellcheck disable=SC1091
  source "$SCRIPT_DIR/local.env"
  set +a
elif [ -f /etc/chatter/deploy.env ]; then
  set -a
  # shellcheck disable=SC1091
  source /etc/chatter/deploy.env
  set +a
fi
VPS=${CHATTER_HOST:+root@$CHATTER_HOST}
VPS=${VPS:-root@YOUR_SERVER_IP}
PORT=${CHATTER_SSH_PORT:-22}
KEY=${KEY:-$HOME/.ssh/chatter_vps}
DEST=${DEST:-$HOME/chatter-backups}
KEEP_DAYS=${KEEP_DAYS:-30}
SSH="ssh -i $KEY -p $PORT -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o ConnectTimeout=20"
mkdir -p "$DEST"
echo "== $(date '+%F %T') pull start"
if command -v rsync >/dev/null 2>&1; then
  rsync -a --delete-after -e "$SSH" "$VPS:/var/backups/chatter/" "$DEST/"
else
  # No rsync on this NAS image: tar over ssh (full copy each night, small data).
  $SSH "$VPS" "tar -C /var/backups -cf - chatter" | tar -C "$DEST/.." -xf -
  mv -f "$DEST/../chatter" "$DEST.tmp" 2>/dev/null && rm -rf "$DEST" && mv "$DEST.tmp" "$DEST"
fi
find "$DEST" -maxdepth 1 -mindepth 1 -type d -mtime +"$KEEP_DAYS" -exec rm -rf {} +
echo "== $(date '+%F %T') pull done: $(du -sh "$DEST" | cut -f1), newest $(ls -1 "$DEST" | sort | tail -1)"
