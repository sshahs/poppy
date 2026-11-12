#!/usr/bin/env bash
# Writes the release keystore from the POPPY_KEYSTORE_BASE64 secret, if present.
# Tag builds REQUIRE a keystore: Android (and Obtainium) refuse updates signed with a different key.
set -euo pipefail
if [[ -n "${POPPY_KEYSTORE_BASE64:-}" ]]; then
  path="$RUNNER_TEMP/poppy-release.jks"
  echo "$POPPY_KEYSTORE_BASE64" | base64 -d > "$path"
  echo "POPPY_KEYSTORE_PATH=$path" >> "$GITHUB_ENV"
  echo "Release keystore configured."
elif [[ "${GITHUB_REF_TYPE:-}" == "tag" ]]; then
  echo "::error::POPPY_KEYSTORE_BASE64 secret is missing; refusing to publish a debug-signed release."
  exit 1
else
  echo "No release keystore; this build is signed with a throwaway debug key."
fi
