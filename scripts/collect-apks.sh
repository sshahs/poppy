#!/usr/bin/env bash
# Copies release APKs to dist/ with Obtainium-friendly names and writes SHA-256 sums.
#   poppy-<version>-arm64-v8a.apk, poppy-<version>-armeabi-v7a.apk, ...
set -euo pipefail
version="${POPPY_VERSION_NAME:?}"
mkdir -p dist
for apk in app/build/outputs/apk/release/app-*-release.apk; do
  abi="$(basename "$apk" | sed -E 's/^app-(.*)-release\.apk$/\1/')"
  cp "$apk" "dist/poppy-${version}-${abi}.apk"
done
(cd dist && sha256sum *.apk > SHA256SUMS.txt)
ls -lh dist
