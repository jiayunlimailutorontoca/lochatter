#!/usr/bin/env bash
# Runs on the NAS (cron, every 5 minutes): probes the chat server; after 3 misses restarts it over ssh, and
# once it answers again posts a card into the assistant's page so both of you know what happened.
#   crontab: */5 * * * * /path/to/hermes/chatter-tools/healthcheck.sh >> /path/to/hermes/chatter-tools/health.log 2>&1
set -uo pipefail
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
DOMAIN=${CHATTER_DOMAIN:-chat.example.com}
URL=${URL:-https://$DOMAIN/healthz}
VPS=${CHATTER_HOST:+root@$CHATTER_HOST}
VPS=${VPS:-root@YOUR_SERVER_IP}
PORT=${CHATTER_SSH_PORT:-22}
KEY=${KEY:-$HOME/.ssh/chatter_vps}
STATE=${STATE:-$HOME/.chatter-health}
ENV_FILE=${ENV_FILE:-/path/to/hermes/data/.env}
mkdir -p "$STATE"
now=$(date '+%F %T')
if curl -fsS -m 15 "$URL" >/dev/null 2>&1; then
  if [ -f "$STATE/down_since" ]; then
    since=$(cat "$STATE/down_since")
    restarted=$( [ -f "$STATE/restarted" ] && echo "，已由 NAS 自动重启" || echo "" )
    rm -f "$STATE/down_since" "$STATE/restarted" "$STATE/misses"
    echo "$now up again (down since $since)"
    # Card through the assistant's own token (its page is plaintext anyway). POST /bot/card is 1.5+.
    token=$(grep -E '^LOCHATTER_TOKEN=' "$ENV_FILE" 2>/dev/null | cut -d= -f2- | tr -d '"\r')
    server=$(grep -E '^LOCHATTER_URL=' "$ENV_FILE" 2>/dev/null | cut -d= -f2- | tr -d '"\r')
    server=${server:-https://$DOMAIN}
    if [ -n "$token" ]; then
      text="服务器恢复了\n${since} 到 ${now} 之间 ${DOMAIN} 没有响应${restarted}。这段时间发的消息会在重连后补上。"
      curl -fsS -m 20 -X POST "$server/bot/card" -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
        --data "{\"text\":\"$text\"}" >/dev/null 2>&1 || echo "$now card failed"
    fi
  fi
  exit 0
fi
misses=$(( $(cat "$STATE/misses" 2>/dev/null || echo 0) + 1 ))
echo "$misses" > "$STATE/misses"
[ -f "$STATE/down_since" ] || echo "$now" > "$STATE/down_since"
echo "$now miss #$misses"
if [ "$misses" -ge 3 ] && [ ! -f "$STATE/restarted" ] && [ -f "$KEY" ]; then
  echo "$now restarting chatter over ssh"
  ssh -i "$KEY" -p "$PORT" -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o ConnectTimeout=20 "$VPS" \
    "systemctl restart chatter; sleep 2; systemctl is-active chatter; systemctl reload nginx || true" && touch "$STATE/restarted"
fi
