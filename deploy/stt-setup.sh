#!/usr/bin/env bash
# Install the speech-to-text backend behind POST /stt: a small C# Native AOT process that calls
# sherpa-onnx SenseVoice (CPU, good Chinese, ~1 s for a 10 s voice note) through native/stt_bridge.c.
# The HTTP shape is unchanged (OpenAI /v1/audio/transcriptions on 127.0.0.1:5090).
#
# The model is the memory. The process exits after 10 minutes with no request so that mapping is
# released; chatter-stt.socket accepts the next connection and starts it again (the first request
# waits while the model loads, a few seconds, inside the chat server's 60 s proxy timeout).
#
# Run as root ON THE SERVER (Ubuntu 24.04), idempotent. The chatter repo must already be checked out:
#   bash /opt/chatter/src/deploy/stt-setup.sh
# Afterwards /etc/chatter/stt.env points chatter-server at http://127.0.0.1:5090 and hello advertises features.stt.
# The Android app recognizes on device by default. This process is only used when someone turns on
# 设置 → 语音识别 and fills this server's https://<host>/stt (or any other transcription URL).
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
PROJ="$ROOT/server/Chatter.Stt/Chatter.Stt.csproj"
BRIDGE="$ROOT/server/Chatter.Stt/native/stt_bridge.c"
STT=/opt/stt
LIB=$STT/lib
MODELS=$STT/models
MODEL_NAME=sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17
MODEL_URL=https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$MODEL_NAME.tar.bz2
MODEL_DIR=$MODELS/$MODEL_NAME
SHERPA_VER=1.13.8
SHERPA_NAME=sherpa-onnx-v${SHERPA_VER}-linux-x64-shared-no-tts
SHERPA_URL=https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VER}/${SHERPA_NAME}.tar.bz2
PORT=5090
IDLE=600
UNIT=/etc/systemd/system/chatter-stt.service
SOCKET=/etc/systemd/system/chatter-stt.socket
BAK=${UNIT}.bak
LOG=$STT/build.log

[ "$(id -u)" = 0 ] || { echo "run as root"; exit 1; }
[ -f "$PROJ" ] || { echo "!! missing $PROJ (deploy the chatter repo first)"; exit 1; }
[ -f "$BRIDGE" ] || { echo "!! missing $BRIDGE"; exit 1; }

echo "== packages"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq >/dev/null 2>&1 || true
apt-get install -y -qq ffmpeg curl bzip2 gcc libc6-dev binutils >/dev/null

echo "== user + directories"
id -u stt >/dev/null 2>&1 || useradd --system --home-dir "$STT" --shell /usr/sbin/nologin stt
mkdir -p "$STT" "$LIB" "$MODELS"

echo "== model ($MODEL_DIR)"
if [ ! -f "$MODEL_DIR/model.int8.onnx" ] || [ ! -f "$MODEL_DIR/tokens.txt" ]; then
  TMP=$(mktemp -d)
  trap 'rm -rf "$TMP"' EXIT
  curl -fL --retry 3 -o "$TMP/model.tar.bz2" "$MODEL_URL"
  # Only the int8 model and the token table are needed (the fp32 model.onnx in the tarball is ~900 MB and unused).
  tar -xjf "$TMP/model.tar.bz2" -C "$MODELS" --wildcards "*/model.int8.onnx" "*/tokens.txt"
  rm -rf "$TMP"
  trap - EXIT
  [ -f "$MODEL_DIR/model.int8.onnx" ] || { echo "!! model layout unexpected:"; find "$MODELS" -maxdepth 2 -type f | head; exit 1; }
else
  echo "   present, skipping download"
fi

echo "== stop old transcriber (frees RAM for the AOT publish)"
systemctl stop chatter-stt.service >/dev/null 2>&1 || true

install_sherpa() {
  if [ "$(cat "$LIB/SHERPA_VERSION" 2>/dev/null || true)" = "$SHERPA_VER" ] \
    && [ -f "$LIB/libsherpa-onnx-c-api.so" ] \
    && [ -f "$STT/include/sherpa-onnx/c-api/c-api.h" ]; then
    echo "== sherpa-onnx $SHERPA_VER already in $LIB"
    return
  fi
  local stage header inc so
  stage=$(mktemp -d)
  # RETURN trap is function-local and does not replace the script's EXIT trap.
  trap 'rm -rf "$stage"' RETURN
  echo "== download sherpa-onnx $SHERPA_VER"
  curl -fL --retry 3 -o "$stage/sherpa.tar.bz2" "$SHERPA_URL"
  tar -xjf "$stage/sherpa.tar.bz2" -C "$stage"
  header=$(find "$stage" -name c-api.h -print -quit)
  so=$(find "$stage" -name 'libsherpa-onnx-c-api.so' -print -quit)
  [ -n "$header" ] && [ -n "$so" ] || { echo "!! sherpa tarball has no c-api.h or libsherpa-onnx-c-api.so"; find "$stage" -maxdepth 3 -type d | head; exit 1; }
  # header is include/sherpa-onnx/c-api/c-api.h; gcc -I wants that include directory.
  inc=$(cd "$(dirname "$header")/../.." && pwd)
  mkdir -p "$LIB" "$STT/include"
  find "$(dirname "$so")" -maxdepth 1 -type f \( -name '*.so' -o -name '*.so.*' \) -exec cp -a {} "$LIB/" \;
  rm -rf "$STT/include"
  mkdir -p "$STT/include"
  cp -a "$inc"/. "$STT/include"/
  echo "$SHERPA_VER" > "$LIB/SHERPA_VERSION"
}

install_sherpa
INC=$STT/include
[ -f "$INC/sherpa-onnx/c-api/c-api.h" ] || { echo "!! missing $INC/sherpa-onnx/c-api/c-api.h"; exit 1; }

echo "== bridge ($LIB/libstt_bridge.so)"
gcc -shared -fPIC -O2 -o "$LIB/libstt_bridge.so" "$BRIDGE" \
  -I"$INC" -L"$LIB" -lsherpa-onnx-c-api \
  -Wl,-rpath,'$ORIGIN' -Wl,-z,origin
strip --strip-unneeded "$LIB/libstt_bridge.so"
missing=$(LD_LIBRARY_PATH="$LIB" ldd "$LIB/libstt_bridge.so" | grep "not found" || true)
if [ -n "$missing" ]; then echo "!! bridge is missing libraries:"; echo "$missing"; exit 1; fi
missing=$(LD_LIBRARY_PATH="$LIB" ldd "$LIB/libsherpa-onnx-c-api.so" | grep "not found" || true)
if [ -n "$missing" ]; then echo "!! libsherpa-onnx-c-api is missing libraries:"; echo "$missing"; exit 1; fi
chmod -R a+rX "$LIB"

echo "== publish chatter-stt (Native AOT)"
export DOTNET_CLI_TELEMETRY_OPTOUT=1 DOTNET_NOLOGO=1 DOTNET_CLI_HOME=/root DOTNET_ROOT=/usr/share/dotnet
export PATH="/usr/share/dotnet:$PATH"
command -v dotnet >/dev/null || { echo "!! dotnet SDK not found at /usr/share/dotnet"; exit 1; }
PUB=$(mktemp -d)
trap 'rm -rf "$PUB"' EXIT
if ! dotnet publish "$PROJ" -c Release -r linux-x64 -o "$PUB" \
  -m:1 -p:UseSharedCompilation=false -nodeReuse:false > "$LOG" 2>&1; then
  echo "!! publish failed"
  grep -E "error|warning IL|warning AOT" "$LOG" | head -30 || true
  tail -n 20 "$LOG"
  exit 1
fi
grep -E "warning (IL|AOT|CS)" "$LOG" | sort -u | head -20 || true
install -m 755 "$PUB/chatter-stt" "$STT/chatter-stt"
rm -rf "$PUB"
trap - EXIT

echo "== systemd socket + service"
if [ -f "$UNIT" ]; then cp -a "$UNIT" "$BAK"; fi
cat > "$SOCKET" <<UNIT
[Unit]
Description=lochatter speech-to-text socket

[Socket]
ListenStream=127.0.0.1:$PORT
Accept=no
NoDelay=true
TriggerLimitIntervalSec=10
TriggerLimitBurst=20

[Install]
WantedBy=sockets.target
UNIT

cat > "$UNIT" <<UNIT
[Unit]
Description=lochatter speech-to-text (sherpa-onnx SenseVoice, C# AOT)
Requires=chatter-stt.socket
After=network.target chatter-stt.socket
StartLimitIntervalSec=120
StartLimitBurst=5

[Service]
Type=simple
User=stt
Group=stt
WorkingDirectory=$STT
ExecStart=$STT/chatter-stt
Environment=STT_MODEL_DIR=$MODEL_DIR
Environment=STT_LIB_DIR=$LIB
Environment=STT_THREADS=2
Environment=STT_IDLE_SEC=$IDLE
Environment=OMP_NUM_THREADS=2
Environment=LD_LIBRARY_PATH=$LIB
Environment=DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=1
Environment=DOTNET_EnableDiagnostics=0
Restart=on-failure
RestartSec=3
MemoryMax=700M
OOMScoreAdjust=500
TimeoutStopSec=20
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
UNIT

# No [Install] on the service: boot enables the socket only. A clean idle exit (status 0) stays down
# until the next connection. Crash exits still restart.
chmod -R o+rX "$STT"

rollback() {
  echo "!! chatter-stt did not come up:"
  journalctl -u chatter-stt.service --no-pager -n 40 || true
  systemctl disable --now chatter-stt.socket >/dev/null 2>&1 || true
  systemctl stop chatter-stt.service >/dev/null 2>&1 || true
  if [ -f "$BAK" ]; then
    echo "== restoring previous unit"
    mv "$BAK" "$UNIT"
    rm -f "$SOCKET"
    systemctl daemon-reload
    systemctl enable chatter-stt.service >/dev/null 2>&1 || true
    systemctl start chatter-stt.service || true
  fi
  exit 1
}

systemctl daemon-reload
systemctl disable chatter-stt.service >/dev/null 2>&1 || true
systemctl enable chatter-stt.socket >/dev/null 2>&1 || true
systemctl restart chatter-stt.socket
systemctl reset-failed chatter-stt.service >/dev/null 2>&1 || true
systemctl restart chatter-stt.service || rollback

echo "== wait for model load"
ok=0
for i in $(seq 1 90); do
  if curl -sf "http://127.0.0.1:$PORT/healthz" >/dev/null 2>&1; then ok=1; break; fi
  sleep 2
done
[ "$ok" = 1 ] || rollback
echo "   healthz: $(curl -s "http://127.0.0.1:$PORT/healthz")"

echo "== /etc/chatter/stt.env"
mkdir -p /etc/chatter
cat > /etc/chatter/stt.env <<ENV
CHATTER_STT_URL=http://127.0.0.1:$PORT/v1/audio/transcriptions
CHATTER_STT_MODEL=sense-voice
ENV
if getent group chatter >/dev/null; then chown root:chatter /etc/chatter/stt.env; else chown root:root /etc/chatter/stt.env; fi
chmod 640 /etc/chatter/stt.env

echo "== smoke test (1 s of silence; expect a JSON object with an empty text field)"
SMOKE="ffmpeg -y -loglevel error -f lavfi -i anullsrc=r=16000:cl=mono -t 1 /tmp/s.wav && curl -sf -F file=@/tmp/s.wav -F model=sense-voice -F language=zh http://127.0.0.1:$PORT/v1/audio/transcriptions"
echo "   $SMOKE"
bash -c "$SMOKE" || { echo; echo "!! smoke test failed"; rollback; }
echo

if [ -d "$STT/venv" ] || [ -f "$STT/server.py" ]; then
  echo "== removing python venv"
  rm -rf "$STT/venv" "$STT/__pycache__" "$STT/server.py"
fi
rm -f "$BAK"

if systemctl list-unit-files chatter.service >/dev/null 2>&1 && systemctl list-unit-files chatter.service | grep -q '^chatter.service'; then
  systemctl restart chatter
  echo "   chatter restarted (picks up stt.env; hello now advertises features.stt)"
fi

echo "== idle: process exits after ${IDLE}s without a request; the socket starts it on the next one"
systemctl --no-pager status chatter-stt.service | sed -n '1,6p;/Memory/p'
systemctl --no-pager status chatter-stt.socket | sed -n '1,5p'
