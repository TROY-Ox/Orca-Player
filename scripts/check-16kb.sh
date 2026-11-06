#!/usr/bin/env bash
# Checks that every 64-bit native library in the given APKs supports 16 KB memory pages,
# as Google Play requires. Run it on any APK: scripts/check-16kb.sh app.apk [more.apk ...]
set -euo pipefail

if [ "$#" -eq 0 ]; then
  echo "Usage: $0 app.apk [more.apk ...]" >&2
  exit 2
fi

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
zipalign=""
if [ -n "$sdk" ] && [ -d "$sdk/build-tools" ]; then
  bt="$(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
  zipalign="$bt/zipalign"
fi

status=0
for apk in "$@"; do
  echo "== $apk"
  if [ ! -f "$apk" ]; then
    echo "Missing file: $apk"
    status=1
    continue
  fi
  work="$(mktemp -d)"
  unzip -q -o "$apk" 'lib/*' -d "$work" 2> /dev/null || true

  # 1. Every LOAD segment of each 64-bit library must be aligned to at least 16 KB.
  found=0
  while IFS= read -r -d '' so; do
    found=1
    rel="${so#"$work"/}"
    ok=1
    while IFS= read -r align; do
      if [ "$(printf '%d' "$align")" -lt 16384 ]; then ok=0; fi
    done < <(readelf -lW "$so" | awk '$1 == "LOAD" { print $NF }')
    if [ "$ok" -eq 1 ]; then
      echo "ok         $rel"
    else
      echo "NOT 16 KB  $rel"
      status=1
    fi
  done < <(find "$work" \( -path '*/lib/arm64-v8a/*' -o -path '*/lib/x86_64/*' \) -name '*.so' -print0)
  if [ "$found" -eq 0 ]; then echo "no 64-bit native libraries"; fi

  # 2. Uncompressed libraries must also sit on 16 KB boundaries inside the APK (build-tools 35+).
  if [ -n "$zipalign" ] && [ -x "$zipalign" ]; then
    if ! "$zipalign" -c -P 16 -v 4 "$apk" > "$work/zipalign.log" 2>&1; then
      grep -E 'BAD|ERROR|Usage' "$work/zipalign.log" || tail -n 5 "$work/zipalign.log"
      echo "Zip alignment check failed for $apk (needs build-tools 35 or newer)"
      status=1
    fi
  else
    echo "zipalign not found, skipped the zip alignment check"
  fi
  rm -rf "$work"
done
exit "$status"
