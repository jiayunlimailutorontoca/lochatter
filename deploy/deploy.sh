#!/usr/bin/env bash
# Server-side build & install. Run as root ON THE SERVER:
#   bash /opt/chatter/src/deploy/deploy.sh [git-ref]      (default: main)
# Flow: fetch the repo (CHATTER_REPO, default /srv/git/chatter.git) -> re-exec that copy
#       -> dotnet publish (Native AOT) -> swap /opt/chatter/rel -> restart.
set -euo pipefail

SRC=/opt/chatter/src
REL=/opt/chatter/rel
REF=${1:-main}
LOG=/opt/chatter/build.log

# Private host, certificate directory, and repo URL live here. Missing file is fine.
if [ -f /etc/chatter/deploy.env ]; then
  set -a
  # shellcheck disable=SC1091
  source /etc/chatter/deploy.env
  set +a
fi
REPO=${CHATTER_REPO:-/srv/git/chatter.git}

if [ "${CHATTER_DEPLOY_STAGE:-}" != "build" ]; then
  mkdir -p /opt/chatter
  if [ ! -d "$SRC/.git" ]; then git clone -q "$REPO" "$SRC"; fi
  git -C "$SRC" fetch -q origin
  git -C "$SRC" checkout -q --detach "origin/$REF"
  # bash reads scripts lazily; re-exec so the rest runs from the NEW checkout, not the old inode.
  CHATTER_DEPLOY_STAGE=build exec bash "$SRC/deploy/deploy.sh" "$REF"
fi

export DOTNET_CLI_TELEMETRY_OPTOUT=1 DOTNET_NOLOGO=1 DOTNET_CLI_HOME=/root DOTNET_ROOT=/usr/share/dotnet
export PATH=/usr/share/dotnet:$PATH

COMMIT=$(git -C "$SRC" rev-parse --short HEAD)
echo "== building $REF @ $COMMIT (log: $LOG)"

START=$(date +%s)
rm -rf "$REL.new"
# -m:1 + no shared compilation keep peak memory low on a small machine.
dotnet publish "$SRC/server/Chatter.Server" -c Release -r linux-x64 -o "$REL.new" \
  -p:SourceRevisionId="$COMMIT" -m:1 -p:UseSharedCompilation=false -nodeReuse:false \
  > "$LOG" 2>&1 || { echo "!! publish failed"; grep -E "error|warning IL|warning AOT" "$LOG" | head -30; tail -n 15 "$LOG"; exit 1; }
grep -E "warning (IL|AOT|CS)" "$LOG" | sort -u | head -20 || true
echo "== publish took $(( $(date +%s) - START ))s"

rm -f "$REL.new"/*.dbg "$REL.new"/*.staticwebassets.endpoints.json
rm -rf "$REL.old"
if [ -d "$REL" ]; then mv "$REL" "$REL.old"; fi
mv "$REL.new" "$REL"
chown -R root:root "$REL" && chmod 755 "$REL" "$REL/chatter-server"

install -m 755 "$SRC/deploy/chatterctl" /usr/local/bin/chatterctl
install -m 755 "$SRC/deploy/chatter-nas-cmd" /usr/local/bin/chatter-nas-cmd
install -m 644 "$SRC/deploy/chatter.service" /etc/systemd/system/chatter.service
mkdir -p /var/www/chatter/web
cp -a "$SRC/web/." /var/www/chatter/web/
DOMAIN=${CHATTER_DOMAIN:-chat.example.com}
SSL_DIR=${CHATTER_SSL_DIR:-$DOMAIN}
rendered=$(mktemp)
sed -e "s/chat\\.example\\.com/${DOMAIN}/g" -e "s#__CERT_DIR__#${SSL_DIR}#g" \
  "$SRC/deploy/nginx-chat.conf" > "$rendered"
install -m 644 "$rendered" "/etc/nginx/sites-available/${DOMAIN}.conf"
ln -sf "/etc/nginx/sites-available/${DOMAIN}.conf" "/etc/nginx/sites-enabled/${DOMAIN}.conf"
rm -f "$rendered"
if [ "$DOMAIN" != "chat.example.com" ]; then
  rm -f /etc/nginx/sites-enabled/chat.example.com.conf /etc/nginx/sites-available/chat.example.com.conf
fi
id -u chatter >/dev/null 2>&1 || useradd --system --home-dir /var/lib/chatter --shell /usr/sbin/nologin chatter
mkdir -p /var/lib/chatter/media
chown chatter:chatter /var/lib/chatter /var/lib/chatter/media
install -m 644 "$SRC/deploy/nginx-stickers-cache.conf" /etc/nginx/conf.d/chatter-stickers-cache.conf
mkdir -p /var/cache/nginx/stickers /var/cache/nginx/tiles
if nginx -t >/dev/null 2>&1; then systemctl reload nginx; else echo "!! nginx config invalid, not reloaded"; nginx -t 2>&1 | tail -3; fi

systemctl daemon-reload
systemctl enable chatter >/dev/null 2>&1 || true
systemctl restart chatter
sleep 1.5
systemctl --no-pager status chatter | sed -n '1,4p;/Memory/p'
echo "== healthz: $(curl -s http://127.0.0.1:5088/healthz)"
echo "== release: $(du -sh "$REL" | cut -f1) total"; ls -la "$REL"
