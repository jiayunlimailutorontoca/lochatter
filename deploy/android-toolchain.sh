#!/usr/bin/env bash
# One-time Android build toolchain for the server (JDK 17 + SDK cmdline-tools + Gradle).
# Run as root: bash deploy/android-toolchain.sh
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
SDK=/opt/android-sdk
GRADLE_VER=8.9

apt-get install -y -qq openjdk-17-jdk-headless unzip zip >/dev/null 2>&1 && echo "jdk: $(java -version 2>&1 | head -1)"

mkdir -p "$SDK/cmdline-tools"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  cd /tmp
  curl -sSL -o cmdtools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  rm -rf "$SDK/cmdline-tools/latest" cmdline-tools
  unzip -q cmdtools.zip && mv cmdline-tools "$SDK/cmdline-tools/latest" && rm cmdtools.zip
fi
export ANDROID_HOME=$SDK JAVA_OPTS="-Xmx512m"
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null 2>&1 || true
"$SDK/cmdline-tools/latest/bin/sdkmanager" --install "platform-tools" "platforms;android-35" "build-tools;35.0.0" 2>&1 | grep -vE "^\s*$|Warning|\[=" | tail -3 || true
echo "sdk: $(ls $SDK/platforms) / $(ls $SDK/build-tools)"

if [ ! -x /opt/gradle/bin/gradle ]; then
  cd /tmp && curl -sSL -o gradle.zip "https://services.gradle.org/distributions/gradle-${GRADLE_VER}-bin.zip"
  rm -rf /opt/gradle "/opt/gradle-${GRADLE_VER}" && unzip -q gradle.zip -d /opt && mv "/opt/gradle-${GRADLE_VER}" /opt/gradle && rm gradle.zip
fi
echo "gradle: $(/opt/gradle/bin/gradle --version 2>/dev/null | grep '^Gradle')"

cat > /etc/profile.d/android.sh <<'EOF'
export ANDROID_HOME=/opt/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH=$PATH:/opt/gradle/bin:/opt/android-sdk/platform-tools:/opt/android-sdk/cmdline-tools/latest/bin
EOF
# Keep Gradle/Kotlin daemons small: the box has 2 GB RAM.
mkdir -p /root/.gradle && cat > /root/.gradle/gradle.properties <<'EOF'
org.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m -XX:+UseSerialGC -Dfile.encoding=UTF-8
org.gradle.daemon=true
org.gradle.parallel=false
org.gradle.workers.max=1
org.gradle.caching=true
kotlin.compiler.execution.strategy=in-process
kotlin.daemon.jvmargs=-Xmx768m
EOF
echo "toolchain ready"; df -h / | tail -1
