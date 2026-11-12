#!/usr/bin/env bash
# Derives POPPY_VERSION_NAME / POPPY_VERSION_CODE from the git ref and appends them to the
# CI environment file ($GITHUB_ENV works on both GitHub Actions and Gitea Actions).
#   v1.2.3      -> name 1.2.3,  code 10203
#   v1.2.3-rc.1 -> name 1.2.3-rc.1, code 10203
#   (no tag)    -> name 0.0.0-<sha>, code = run number
set -euo pipefail

# POPPY_RELEASE_TAG (manual "Run workflow" releases) takes precedence over the git ref.
ref="${POPPY_RELEASE_TAG:-${GITHUB_REF_NAME:-}}"
ref_type="$([[ -n "${POPPY_RELEASE_TAG:-}" ]] && echo tag || echo "${GITHUB_REF_TYPE:-}")"
if [[ -n "${POPPY_RELEASE_TAG:-}" && ! "$POPPY_RELEASE_TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+ ]]; then
  echo "::error::release_version must look like 1.2.3 (got '${POPPY_RELEASE_TAG#v}')"
  exit 1
fi
if [[ "$ref_type" == "tag" && "$ref" =~ ^v([0-9]+)\.([0-9]+)\.([0-9]+)(.*)$ ]]; then
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
