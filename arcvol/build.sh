#!/usr/bin/env bash
# Builds ARC Volume without Gradle or root, on any x86_64 Linux machine:
#   cd arcvol && ./build.sh
# Uses a portable JDK and a minimal SDK in the parent folder (downloaded on first run;
# override the location with BASE=/some/dir).
set -euo pipefail

BASE="${BASE:-$(cd "$(dirname "$0")/.." && pwd)}"
JDK="$BASE/jdk"
SDK="$BASE/android-sdk"
BT_VER=34.0.0
API=28
CMDLINE_ZIP=commandlinetools-linux-11076708_latest.zip
cd "$(dirname "$0")"

# 1. Portable JDK (Temurin 17)
if [ ! -x "$JDK/bin/javac" ]; then
    mkdir -p "$JDK"
    curl -fsSL "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse" \
        | tar -xz -C "$JDK" --strip-components=1
fi
export JAVA_HOME="$JDK" PATH="$JDK/bin:$PATH"

# 2. Minimal SDK: build-tools + Android 9 android.jar
if [ ! -x "$SDK/build-tools/$BT_VER/aapt2" ] || [ ! -f "$SDK/platforms/android-$API/android.jar" ]; then
    if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
        curl -fsSL -o /tmp/cmdline.zip "https://dl.google.com/android/repository/$CMDLINE_ZIP"
        mkdir -p "$SDK/cmdline-tools"
        unzip -q /tmp/cmdline.zip -d "$SDK/cmdline-tools"
        mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
        rm /tmp/cmdline.zip
    fi
    yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null || true  # `yes` dies with SIGPIPE
    "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" "build-tools;$BT_VER" "platforms;android-$API"
fi

BT="$SDK/build-tools/$BT_VER"
JAR="$SDK/platforms/android-$API/android.jar"
rm -rf build && mkdir -p build/res build/classes build/dex

# 3. Resources + manifest
"$BT/aapt2" compile --dir res -o build/res
"$BT/aapt2" link -o build/unsigned.apk -I "$JAR" --manifest AndroidManifest.xml \
    --java build/gen build/res/*.flat

# 4. Java -> .class -> .dex, then add classes.dex to the APK
javac --release 8 -Xlint:-options -classpath "$JAR" -d build/classes $(find src build/gen -name '*.java')
"$BT/d8" --min-api $API --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
python3 -c 'import sys, zipfile; zipfile.ZipFile(sys.argv[1], "a").write(sys.argv[2], "classes.dex")' \
    build/unsigned.apk build/dex/classes.dex

# 5. Align and sign with a local key (generated once and kept next to the project)
if [ ! -f arcvol.keystore ]; then
    keytool -genkeypair -keystore arcvol.keystore -storepass arcvol -keypass arcvol \
        -alias arcvol -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=ARC Volume" >/dev/null
fi
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks arcvol.keystore --ks-pass pass:arcvol --out arcvol.apk build/aligned.apk
"$BT/apksigner" verify arcvol.apk && echo "OK: $(pwd)/arcvol.apk"
