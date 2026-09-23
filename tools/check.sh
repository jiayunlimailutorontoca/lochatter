#!/usr/bin/env bash
# Pre-release check, run from the repo root on the dev PC (Git Bash):
#   bash tools/check.sh            # server build + Android compile + unit tests
#   bash tools/check.sh --e2e      # additionally starts the built server locally and runs tools/e2e.py
# Needs: dotnet SDK 10, JDK 17 + Gradle 8.9 + SDK 35 as described in README (安卓包也可以在自己电脑上编).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
export JAVA_HOME="${JAVA_HOME:-C:\\Program Files\\Eclipse Adoptium\\jdk-17.0.20.101-hotspot}"
export PATH="/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot/bin:/c/Android/gradle/bin:$PATH"

echo "== server: dotnet build"
dotnet build "$ROOT/server/Chatter.Server" -c Release -nologo -v q
dotnet build "$ROOT/server/Chatter.Stt" -c Release -nologo -v q

echo "== android: compile + unit tests"
( cd "$ROOT/android" && gradle --no-daemon --console=plain -q :app:compileReleaseKotlin :app:testReleaseUnitTest )

if [ "${1:-}" = "--e2e" ]; then
  echo "== e2e against a local server"
  DATA=$(mktemp -d)
  BIN=$(ls "$ROOT"/server/Chatter.Server/bin/Release/net10.0/chatter-server.exe 2>/dev/null || ls "$ROOT"/server/Chatter.Server/bin/Release/net10.0/chatter-server)
  export CHATTER_DATA="$DATA" CHATTER_SWEEP_SECONDS=1 CHATTER_MEDIA_GRACE_SECONDS=2 CHATTER_TURN_HOST=127.0.0.1 CHATTER_TURN_SECRET=e2e-turn-secret ASPNETCORE_URLS=http://127.0.0.1:5089
  "$BIN" user add alice alicepass >/dev/null
  "$BIN" user add bob bobpass >/dev/null
  BOT_TOKEN=$("$BIN" bot token)
  "$BIN" & SRV=$!
  trap 'kill $SRV 2>/dev/null; rm -rf "$DATA"' EXIT
  sleep 2
  PY=$(ls -d "$HOME"/AppData/Roaming/uv/python/cpython-3.11*/python.exe 2>/dev/null | head -1)
  BOT_TOKEN="$BOT_TOKEN" "${PY:-python3}" "$ROOT/tools/e2e.py" http://127.0.0.1:5089 alice alicepass bob bobpass
fi
echo "== all good"
