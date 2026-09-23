#!/usr/bin/env bash
# Builds the Android release APK ON THE SERVER and publishes it under /apk/chatter.apk
#   bash /opt/chatter/src/deploy/android-build.sh
# Needs deploy/android-toolchain.sh to have run once (JDK 17, SDK 35, Gradle 8.9).
# Download host comes from CHATTER_DOMAIN in /etc/chatter/deploy.env (default chat.example.com).
set -euo pipefail
if [ -f /etc/chatter/deploy.env ]; then
  set -a
  # shellcheck disable=SC1091
  source /etc/chatter/deploy.env
  set +a
fi
DOMAIN=${CHATTER_DOMAIN:-chat.example.com}
DOMAIN=${DOMAIN#https://}
DOMAIN=${DOMAIN#http://}
DOMAIN=${DOMAIN%/}
export CHATTER_SERVER_URL="https://${DOMAIN}"
SRC=/opt/chatter/src
OUT=/var/www/chatter
LOG=/opt/chatter/android-build.log
KS=/etc/chatter/android.jks
PROPS=/etc/chatter/keystore.properties

# shellcheck disable=SC1091
source /etc/profile.d/android.sh

if [ ! -f "$KS" ]; then
  PW=$(openssl rand -base64 24 | tr -d '/+=' | cut -c1-24)
  keytool -genkeypair -keystore "$KS" -alias chatter -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$PW" -keypass "$PW" -dname "CN=chatter" >/dev/null 2>&1
  printf 'storeFile=%s\nstorePassword=%s\nkeyAlias=chatter\nkeyPassword=%s\n' "$KS" "$PW" "$PW" > "$PROPS"
  chmod 600 "$KS" "$PROPS"
  echo "== generated release keystore $KS"
fi
cp "$PROPS" "$SRC/android/keystore.properties"

cd "$SRC/android"
START=$(date +%s)
echo "== gradle :app:assembleRelease (log: $LOG)"
if ! gradle --no-daemon --console=plain :app:assembleRelease > "$LOG" 2>&1; then
  echo "!! gradle failed"
  grep -nE "^e: |error:|FAILURE|What went wrong|Exception|Could not" -A4 "$LOG" | head -80
  exit 1
fi
echo "== gradle took $(( $(date +%s) - START ))s"
grep -E "^w: " "$LOG" | head -10 || true

VER=$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)
APK=app/build/outputs/apk/release/app-release.apk
mkdir -p "$OUT"
cp "$APK" "$OUT/chatter-$VER.apk"
ln -sf "chatter-$VER.apk" "$OUT/chatter.apk"
VCODE=$(grep -oP 'versionCode = \K[0-9]+' app/build.gradle.kts)
NOTES=$(git -C "$SRC" log -1 --pretty=%s | sed 's/"/\\"/g')
# In-app update check reads this (see UpdateChecker.kt).
printf '{"versionCode":%s,"versionName":"%s","url":"https://%s/apk/chatter-%s.apk","size":%s,"notes":"%s"}\n' \
  "$VCODE" "$VER" "$DOMAIN" "$VER" "$(stat -c %s "$APK")" "$NOTES" > "$OUT/latest.json"
ls -la "$OUT"
echo "== download: https://$DOMAIN/apk/chatter.apk ($(du -h "$APK" | cut -f1))"

# shellcheck disable=SC1090
source "$PROPS" 2>/dev/null || true
if [ -n "${storePassword:-}" ]; then
  echo "== signing cert SHA-256: $(keytool -list -v -keystore "$KS" -storepass "$storePassword" -alias chatter 2>/dev/null | grep -oP 'SHA256: \K.*')"
fi
