#!/usr/bin/env bash
# Derives the release identity for a build from git history.
#
#   versionName  next patch on the major.minor line of vplayer.baseVersion,
#                taking existing v<major>.<minor>.<patch>[-build.<code>] tags
#                into account, unless VPLAYER_VERSION_NAME overrides it.
#   versionCode  1000000 + the commit count, unless VPLAYER_VERSION_CODE
#                overrides it.
#
# Usage: android-release-meta.sh [--format=json|github-output]
set -euo pipefail

FORMAT="json"
for arg in "$@"; do
  case "$arg" in
    --format=*) FORMAT="${arg#--format=}" ;;
    *) echo "Unknown argument: $arg" >&2; exit 1 ;;
  esac
done

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

VERSION_CODE_BASE=1000000

base_version="$(sed -n 's/^vplayer\.baseVersion=//p' gradle.properties | tr -d '[:space:]')"
if [[ ! "$base_version" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
  echo "Expected vplayer.baseVersion in gradle.properties to be x.y.z, got: ${base_version:-<empty>}" >&2
  exit 1
fi
major="${BASH_REMATCH[1]}"
minor="${BASH_REMATCH[2]}"
base_patch="${BASH_REMATCH[3]}"

if [[ -n "${VPLAYER_VERSION_NAME:-}" ]]; then
  version_name="$VPLAYER_VERSION_NAME"
else
  highest=$((base_patch - 1))
  while read -r tag; do
    [[ -z "$tag" ]] && continue
    if [[ "$tag" =~ ^v([0-9]+)\.([0-9]+)\.([0-9]+)(-build\.[0-9]+)?$ ]]; then
      if [[ "${BASH_REMATCH[1]}" == "$major" && "${BASH_REMATCH[2]}" == "$minor" ]]; then
        patch="${BASH_REMATCH[3]}"
        (( patch > highest )) && highest="$patch"
      fi
    fi
  done < <(git tag --list 2>/dev/null || true)
  version_name="${major}.${minor}.$((highest + 1))"
fi

if [[ -n "${VPLAYER_VERSION_CODE:-}" ]]; then
  version_code="$VPLAYER_VERSION_CODE"
else
  commit_count="$(git rev-list --count HEAD 2>/dev/null || echo 0)"
  version_code=$((VERSION_CODE_BASE + commit_count))
fi

if [[ ! "$version_code" =~ ^[0-9]+$ ]] || (( version_code <= 0 )); then
  echo "Invalid Android versionCode: $version_code" >&2
  exit 1
fi

apk_name="vplayer-$(printf '%s' "$version_name" | sed 's/[^0-9A-Za-z._-]/-/g').apk"
release_tag="v${version_name}-build.${version_code}"

case "$FORMAT" in
  json)
    printf '{"apk_name":"%s","app_version":"%s","build_number":"%s","release_tag":"%s","versionCode":%s,"versionName":"%s"}\n' \
      "$apk_name" "$version_name" "$version_code" "$release_tag" "$version_code" "$version_name"
    ;;
  github-output)
    printf 'apk_name=%s\napp_version=%s\nbuild_number=%s\nrelease_tag=%s\n' \
      "$apk_name" "$version_name" "$version_code" "$release_tag"
    ;;
  *)
    echo "Unsupported output format: $FORMAT" >&2
    exit 1
    ;;
esac
