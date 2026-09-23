#!/usr/bin/env bash
# Weekly housekeeping ON THE SERVER: apt cache, docker leftovers, old APKs, old releases, logs.
set -euo pipefail
apt-get clean
docker system prune -f >/dev/null 2>&1 || true
docker image prune -af --filter "until=168h" >/dev/null 2>&1 || true
# keep the 3 newest versioned APKs (chatter.apk symlink always points at the newest)
ls -t /var/www/chatter/chatter-*.apk 2>/dev/null | tail -n +4 | xargs -r rm -f
rm -rf /opt/chatter/rel.old /opt/chatter/src/android/app/build /root/.gradle/daemon
journalctl --vacuum-time=14d >/dev/null 2>&1 || true
find /var/log -name "*.gz" -mtime +14 -delete 2>/dev/null || true
echo "cleanup done: $(df -h / | awk 'NR==2{print $4" free"}')"
