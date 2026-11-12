#!/usr/bin/env bash
# Derives POPPY_VERSION_NAME / POPPY_VERSION_CODE from the git ref and appends them to the
# CI environment file ($GITHUB_ENV works on both GitHub Actions and Gitea Actions).
#   v1.2.3      -> name 1.2.3,  code 10203
#   v1.2.3-rc.1 -> name 1.2.3-rc.1, code 10203
#   (no tag)    -> name 0.0.0-<sha>, code = run number
set -euo pipefail

ref="${GITHUB_REF_NAME:-}"
if [[ "${GITHUB_REF_TYPE:-}" == "tag" && "$ref" =~ ^v([0-9]+)\.([0-9]+)\.([0-9]+)(.*)$ ]]; then
  name="${ref#v}"
  code=$(( BASH_REMATCH[1] * 10000 + BASH_REMATCH[2] * 100 + BASH_REMATCH[3] ))
  prerelease=$([[ -n "${BASH_REMATCH[4]}" ]] && echo true || echo false)
else
  name="0.0.0-$(git rev-parse --short HEAD)"
  code="${GITHUB_RUN_NUMBER:-1}"
  prerelease=true
fi

{
  echo "POPPY_VERSION_NAME=$name"
  echo "POPPY_VERSION_CODE=$code"
  echo "POPPY_PRERELEASE=$prerelease"
} >> "${GITHUB_ENV:-/dev/stdout}"
echo "Version $name ($code)"
