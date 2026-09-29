#!/usr/bin/env bash
# Build the Torrin MDBList CloudStream plugin as a .cs3 file.
#
# .cs3 format (per recloudstream docs + this fork's PluginManager):
#   zip archive containing classes*.dex and manifest.json
#
# The module compiles against :library (compileOnly), so the jar holds only
# the plugin's own classes — exactly what the app expects in a .cs3.
set -euo pipefail

cd /home/rootx/cloudstream
export JAVA_HOME=/home/rootx/jdk-21.0.12.1+1
export ANDROID_HOME=/home/rootx/Android/Sdk
export PATH="$JAVA_HOME/bin:$PATH"

echo "==> Compiling plugin module"
./gradlew :plugins:torrin-mdblist:jar -q

BT="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)"
API="$(ls "$ANDROID_HOME/platforms" | sed 's/^android-//' | sort -n | tail -1)"
ANDROID_JAR="$ANDROID_HOME/platforms/android-$API/android.jar"
D8="$BT/d8"

JAR="$(find plugins/torrin-mdblist/build/libs -name '*.jar' | head -1)"
echo "==> jar: $JAR"

OUT=plugins/torrin-mdblist/build/cs3
rm -rf "$OUT"
mkdir -p "$OUT/dex"

echo "==> dex (d8, min-api 24)"
"$D8" --release --lib "$ANDROID_JAR" --min-api 24 --output "$OUT/dex" "$JAR"

cp plugins/torrin-mdblist/manifest.json "$OUT/manifest.json"

echo "==> packaging .cs3"
cd "$OUT"
python3 - <<'PY'
import os, zipfile
dexes = sorted(f for f in os.listdir("dex") if f.endswith(".dex"))
with zipfile.ZipFile("TorrinMdbList.cs3", "w", zipfile.ZIP_DEFLATED) as z:
    for d in dexes:
        z.write(os.path.join("dex", d), d)
    z.write("manifest.json", "manifest.json")
print("  dex:", ", ".join(dexes))
PY
cd /home/rootx/cloudstream

echo "==> done: plugins/torrin-mdblist/build/cs3/TorrinMdbList.cs3"
ls -la plugins/torrin-mdblist/build/cs3/TorrinMdbList.cs3
