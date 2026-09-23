#!/usr/bin/env bash
# Nightly backup ON THE SERVER: chatter SQLite (online backup) + media + /etc/chatter.
# Keeps 7 days under /var/backups/chatter. Installed by deploy/maintenance-cron.sh.
# Optional: CHATTER_EXTRA_BACKUP in /etc/chatter/deploy.env is a shell command whose stdout
# is saved as extra.dump. That file is not in git.
set -euo pipefail
if [ -f /etc/chatter/deploy.env ]; then
  set -a
  # shellcheck disable=SC1091
  source /etc/chatter/deploy.env
  set +a
fi
OUT=/var/backups/chatter
DAY=$(date +%F)
mkdir -p "$OUT/$DAY"
# consistent SQLite copy while the server runs (WAL mode)
sqlite3 /var/lib/chatter/chatter.db ".backup '$OUT/$DAY/chatter.db'"
tar -czf "$OUT/$DAY/media.tgz" -C /var/lib/chatter media 2>/dev/null || true
cp -a /etc/chatter "$OUT/$DAY/etc-chatter"
if [ -n "${CHATTER_EXTRA_BACKUP:-}" ]; then
  bash -c "$CHATTER_EXTRA_BACKUP" > "$OUT/$DAY/extra.dump" || echo "!! extra backup failed"
fi
chmod -R go-rwx "$OUT/$DAY"
find "$OUT" -mindepth 1 -maxdepth 1 -type d -mtime +7 -exec rm -rf {} +
echo "backup $DAY: $(du -sh "$OUT/$DAY" | cut -f1), total $(du -sh "$OUT" | cut -f1)"
