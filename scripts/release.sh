#!/usr/bin/env bash
set -euo pipefail

GRADLE="build.gradle.kts"
CORE="revnix-core/src/main/kotlin/com/revnix/RevnixClient.kt"
KMP="revnix-kmp/src/commonMain/kotlin/com/revnix/kmp/RevnixClient.kt"
CHANGELOG="CHANGELOG.md"

bump="auto"
wanted=""
app_sha=""
changed=""
notes_out=""
notes_for=""
dry_run=""

while [ $# -gt 0 ]; do
  case "$1" in
    --bump) bump="$2"; shift 2 ;;
    --version) wanted="$2"; shift 2 ;;
    --app-sha) app_sha="$2"; shift 2 ;;
    --changed) changed="$2"; shift 2 ;;
    --notes-out) notes_out="$2"; shift 2 ;;
    --notes-for) notes_for="$2"; shift 2 ;;
    --dry-run) dry_run=1; shift ;;
    *) echo "release.sh: unknown argument $1" >&2; exit 1 ;;
  esac
done

fail() { echo "release.sh: $*" >&2; exit 1; }
gradle_version() { sed -n 's/^[[:space:]]*version = "\([^"]*\)".*/\1/p' "$GRADLE"; }
sdk_version() { sed -n 's/.*const val SDK_VERSION = "\([^"]*\)".*/\1/p' "$1"; }

entry_for() {
  awk -v want="## $1" '
    $0 == want { found = 1; next }
    found && /^## / { exit }
    found { print }
  ' "$CHANGELOG" | sed -e '/./,$!d' | awk 'BEGIN { blank = 0 }
    /^[[:space:]]*$/ { blank++; next }
    { while (blank-- > 0) print ""; blank = 0; print }'
}

release_notes() {
  printf '%s\n' "$1" | sed \
    -e 's/^### Breaking$/## ⚠️ Breaking/' \
    -e 's/^### Removed$/## 🗑️ Removed/' \
    -e 's/^### Added$/## ✨ Added/' \
    -e 's/^### Changed$/## 🔧 Changed/' \
    -e 's/^### Fixed$/## 🐛 Fixed/'
  if [ -n "$2" ] && git rev-parse -q --verify "refs/tags/v$2" >/dev/null 2>&1; then
    printf '\n**Full Changelog**: https://github.com/Oth-tech/RevnixSDK-Android/compare/v%s...v%s\n' "$2" "$3"
  fi
}

previous_version() {
  awk -v want="$1" '
    found && /^## [0-9]/ { sub(/^## /, ""); sub(/ .*/, ""); print; exit }
    index($0, "## " want) == 1 { found = 1 }
  ' "$CHANGELOG"
}

if [ -n "$notes_for" ]; then
  [ -f "$CHANGELOG" ] || fail "no $CHANGELOG"
  heading=$(grep -m1 "^## $notes_for\( \|$\)" "$CHANGELOG" || true)
  [ -n "$heading" ] || fail "$CHANGELOG has no \"## $notes_for\" entry"
  body=$(entry_for "${heading#\#\# }")
  notes=$(release_notes "$body" "$(previous_version "$notes_for")" "$notes_for")
  [ -n "$notes_out" ] && printf '%s\n' "$notes" > "$notes_out"
  printf '%s\n' "$notes"
  exit 0
fi

case "$bump" in
  auto|major|minor|patch) ;;
  *) fail "--bump must be auto|major|minor|patch, got $bump" ;;
esac
if [ -n "$wanted" ]; then
  echo "$wanted" | grep -qE '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$' \
    || fail "--version must be x.y.z, got $wanted"
fi

for f in "$GRADLE" "$CORE" "$KMP" "$CHANGELOG"; do
  [ -f "$f" ] || fail "no $f"
done

current=$(gradle_version)
[ -n "$current" ] || fail "could not read version from $GRADLE"
echo "$current" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$' \
  || fail "$GRADLE version $current is not x.y.z"

for f in "$CORE" "$KMP"; do
  [ "$(sdk_version "$f")" = "$current" ] \
    || fail "$f says $(sdk_version "$f") but $GRADLE says $current, reconcile them first"
done

unreleased=$(entry_for "Unreleased")

count_under() {
  printf '%s\n' "$unreleased" | awk -v want="### $1" '
    $0 ~ "^### " { active = ($0 == want); next }
    active && /^[[:space:]]*-/ { n++ }
    END { print n + 0 }'
}
total_bullets=$(printf '%s\n' "$unreleased" | grep -cE '^[[:space:]]*-' || true)
breaking=$(count_under "Breaking")
added=$(count_under "Added")

if [ "$bump" != "auto" ]; then
  level="$bump"
elif [ "$breaking" -gt 0 ]; then
  level="major"
elif [ "$added" -gt 0 ]; then
  level="minor"
else
  level="patch"
fi

major=${current%%.*}
rest=${current#*.}
minor=${rest%%.*}
patch=${rest#*.}
case "$level" in
  major) next="$((major + 1)).0.0" ;;
  minor) next="$major.$((minor + 1)).0" ;;
  patch) next="$major.$minor.$((patch + 1))" ;;
esac

if [ -n "$wanted" ]; then
  highest=$(printf '%s\n%s\n' "$current" "$wanted" | sort -V | tail -1)
  [ "$wanted" != "$current" ] && [ "$highest" = "$wanted" ] \
    || fail "--version $wanted is not above $current"
  IFS=. read -r wmajor wminor _ <<< "$wanted"
  if [ "$wmajor" != "$major" ]; then level="major"
  elif [ "$wminor" != "$minor" ]; then level="minor"
  else level="patch"; fi
  next="$wanted"
fi

lockstep=false
body=$(printf '%s\n' "$unreleased" | sed -e '/./,$!d')
if [ "$total_bullets" -eq 0 ]; then
  lockstep=true
  if [ "$changed" = "true" ]; then
    body="Changes since $current were not recorded here; see the commit log."
  else
    ride=""
    [ -n "$app_sha" ] && ride=" ${app_sha:0:7}"
    body="No SDK changes. Version moved in lockstep with revnix-app release${ride}; the package contents are identical to $current."
  fi
fi

today=$(date -u +%Y-%m-%d)
summary="$level bump, ${total_bullets} changelog bullet(s), lockstep=$lockstep"

if [ -n "$notes_out" ]; then
  release_notes "$body" "$current" "$next" > "$notes_out"
fi

if [ -z "$dry_run" ]; then
  tmp=$(mktemp)
  {
    awk '/^## Unreleased/ { exit } { print }' "$CHANGELOG"
    printf '## Unreleased\n\n## %s (%s)\n\n' "$next" "$today"
    printf '%s\n\n' "$body"
    awk 'skip { print } /^## Unreleased/ { skip = 1; next }' "$CHANGELOG" \
      | awk 'found { print } /^## / { found = 1; print }'
  } > "$tmp"
  mv "$tmp" "$CHANGELOG"

  perl -pi -e "s/^(\s*version = \")[^\"]*(\")/\${1}$next\${2}/" "$GRADLE"
  perl -pi -e "s/(const val SDK_VERSION = \")[^\"]*(\")/\${1}$next\${2}/" "$CORE" "$KMP"
  perl -pi -e "s/(io\.revnix:revnix-(?:android|core):)[0-9.]+/\${1}$next/g" README.md
fi

echo "$current -> $next ($summary)"

if [ -n "${GITHUB_OUTPUT:-}" ]; then
  {
    echo "current=$current"
    echo "next=$next"
    echo "level=$level"
    echo "lockstep=$lockstep"
    echo "summary=$summary"
  } >> "$GITHUB_OUTPUT"
fi
