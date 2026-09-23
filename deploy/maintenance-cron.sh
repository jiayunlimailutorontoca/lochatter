#!/usr/bin/env bash
# One-time: install backup (03:17 daily) and cleanup (Sunday 04:07) cron jobs. Run as root on the server.
set -euo pipefail
install -m 755 /opt/chatter/src/deploy/backup.sh /usr/local/bin/chatter-backup
install -m 755 /opt/chatter/src/deploy/cleanup.sh /usr/local/bin/chatter-cleanup
cat > /etc/cron.d/chatter-maintenance <<'CRON'
17 3 * * * root /usr/local/bin/chatter-backup >> /var/log/chatter-backup.log 2>&1
7 4 * * 0 root /usr/local/bin/chatter-cleanup >> /var/log/chatter-cleanup.log 2>&1
CRON
chmod 644 /etc/cron.d/chatter-maintenance
echo "installed /etc/cron.d/chatter-maintenance"
