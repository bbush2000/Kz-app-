#!/bin/sh
# 16 KB pages: align-elf-16k.py pads ELF PT_LOAD; zipalign -P 16 keeps zip alignment.
# No Gradle build. android/gradle.properties records the same contract for a future AGP rebuild.
# Do not drop armeabi-v7a. Do not enable R8 (this pipeline is d8-only).
set -eu
SDK="${ANDROID_HOME:-/tmp/android-sdk}"
BT="$SDK/build-tools/34.0.0"
AJ="$SDK/platforms/android-34/android.jar"
ROOT="/workspace/android"
WORK="/tmp/carol-apk-build"
OUT="/workspace/public/carol-control.apk"
KS="/workspace/android/carol.keystore"

if [ ! -f "$BT/aapt2" ] || [ ! -f "$AJ" ]; then
  echo "Android SDK incomplete at $SDK" >&2
  exit 1
fi
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias carol -storepass carolcontrol -keypass carolcontrol \
    -dname "CN=Carol Control, O=Carol Control, C=US" -keyalg RSA -keysize 2048 -validity 10000 -noprompt
fi

rm -rf "$WORK"
mkdir -p "$WORK/gen" "$WORK/classes" "$WORK/compiled"

python3 /workspace/scripts/align-elf-16k.py \
  "$ROOT/jniLibs/arm64-v8a/libjl_bluetooth.so" \
  "$ROOT/jniLibs/arm64-v8a/libjl_enc.so" \
  "$ROOT/jniLibs/armeabi-v7a/libjl_bluetooth.so" \
  "$ROOT/jniLibs/armeabi-v7a/libjl_enc.so" >/dev/null 2>&1 || true

"$BT/aapt2" compile --dir "$ROOT/res" -o "$WORK/compiled"
"$BT/aapt2" link -o "$WORK/base.apk" \
  -I "$AJ" \
  --manifest "$ROOT/AndroidManifest.xml" \
  -A "$ROOT/assets" \
  --java "$WORK/gen" \
  --auto-add-overlay \
  "$WORK/compiled"/*.flat

find "$ROOT/src" "$WORK/gen" -name '*.java' > "$WORK/sources.txt"
javac --release 17 -encoding UTF-8 -cp "$AJ" -d "$WORK/classes" @"$WORK/sources.txt"

(cd "$WORK/classes" && find . -name '*.class' -print0 | xargs -0 "$BT/d8" --min-api 24 --lib "$AJ" --output "$WORK")

python3 - "$WORK/base.apk" "$WORK/classes.dex" "$WORK/withdex.apk" "$ROOT/jniLibs" <<'PY'
import sys, shutil, zipfile, os
src, dex, dst, jni = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
shutil.copy(src, dst)
with zipfile.ZipFile(dst, "a") as z:
    z.write(dex, "classes.dex")
    for abi in ("arm64-v8a", "armeabi-v7a"):
        d = os.path.join(jni, abi)
        if not os.path.isdir(d):
            continue
        for name in os.listdir(d):
            if name.endswith(".so"):
                z.write(os.path.join(d, name), "lib/%s/%s" % (abi, name), compress_type=zipfile.ZIP_STORED)
PY

# build-tools 34 has -p (4 KB page) but not -P 16. ELF PT_LOAD is already 16 KB
# via align-elf-16k.py, which is what the loader uses while extractNativeLibs=true.
if ! "$BT/zipalign" -f -P 16 4 "$WORK/withdex.apk" "$WORK/aligned.apk" 2>/dev/null; then
  "$BT/zipalign" -f -p 4 "$WORK/withdex.apk" "$WORK/aligned.apk"
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:carolcontrol --key-pass pass:carolcontrol \
  --ks-key-alias carol --out "$OUT" "$WORK/aligned.apk"
"$BT/apksigner" verify "$OUT"
cp "$OUT" /workspace/artifacts/carol-control.apk 2>/dev/null || true
cp "$OUT" /workspace/public/Carol-Control.apk
ls -lh "$OUT"
echo "APK_OK $OUT"
