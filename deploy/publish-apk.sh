#!/usr/bin/env bash
# Publishes an APK built elsewhere (e.g. on the developer's PC) at /apk/chatter.apk
#   bash /opt/chatter/src/deploy/publish-apk.sh /tmp/app-release.apk <versionName> <versionCode> ["release notes"]
# Download host comes from CHATTER_DOMAIN in /etc/chatter/deploy.env (default chat.example.com).
set -euo pipefail
if [ -f /etc/chatter/deploy.env ]; then
  set -a
  # shellcheck disable=SC1091
  source /etc/chatter/deploy.env
  set +a
fi
DOMAIN=${CHATTER_DOMAIN:-chat.example.com}
APK=${1:?apk path}
VER=${2:?versionName}
VCODE=${3:?versionCode}
NOTES=${4:-"lochatter $VER"}
OUT=/var/www/chatter
mkdir -p "$OUT"
cp "$APK" "$OUT/chatter-$VER.apk"
ln -sf "chatter-$VER.apk" "$OUT/chatter.apk"
NOTES_ESC=$(printf '%s' "$NOTES" | sed 's/"/\\"/g')
printf '{"versionCode":%s,"versionName":"%s","url":"https://%s/apk/chatter-%s.apk","size":%s,"notes":"%s"}\n' \
  "$VCODE" "$VER" "$DOMAIN" "$VER" "$(stat -c %s "$APK")" "$NOTES_ESC" > "$OUT/latest.json"
chmod 644 "$OUT"/chatter-"$VER".apk "$OUT/latest.json"
ls -la "$OUT"
echo "== published: https://$DOMAIN/apk/chatter.apk ($(du -h "$APK" | cut -f1))"
