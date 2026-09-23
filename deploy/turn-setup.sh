#!/usr/bin/env bash
# Installs and configures coturn (STUN/TURN) for WebRTC calls. Run as root on the server:
#   bash /opt/chatter/src/deploy/turn-setup.sh
# Creates /etc/chatter/turn.env (shared secret, read by chatter.service) and /etc/turnserver.conf.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
PUBLIC_IP=$(curl -4 -s --max-time 10 https://api.ipify.org || ip -4 -o addr show scope global | awk '{print $4}' | cut -d/ -f1 | head -1)
ENV=/etc/chatter/turn.env
SRC=/opt/chatter/src

apt-get install -y -qq coturn >/dev/null 2>&1 && echo "coturn: $(turnserver --version 2>&1 | head -1 | cut -c1-40)"

mkdir -p /etc/chatter
if [ ! -f "$ENV" ]; then
  SECRET=$(openssl rand -hex 32)
  printf 'CHATTER_TURN_SECRET=%s\nCHATTER_TURN_HOST=%s\n' "$SECRET" "$PUBLIC_IP" > "$ENV"
  chmod 640 "$ENV" && chown root:chatter "$ENV"
  echo "== wrote $ENV"
fi
# shellcheck disable=SC1090
source "$ENV"

sed -e "s|@SECRET@|$CHATTER_TURN_SECRET|g" -e "s|@IP@|$CHATTER_TURN_HOST|g" "$SRC/deploy/turnserver.conf" > /etc/turnserver.conf
chmod 640 /etc/turnserver.conf && chown root:turnserver /etc/turnserver.conf
grep -q '^TURNSERVER_ENABLED=1' /etc/default/coturn 2>/dev/null || echo 'TURNSERVER_ENABLED=1' >> /etc/default/coturn
mkdir -p /var/log/coturn && chown turnserver:turnserver /var/log/coturn

systemctl enable coturn >/dev/null 2>&1 || true
systemctl restart coturn
sleep 1
systemctl --no-pager status coturn | sed -n '1,3p'
ss -ulnp | grep 3478 | head -2
echo "== turn host $CHATTER_TURN_HOST, ports udp/tcp 3478, relay udp 49160-49200"
