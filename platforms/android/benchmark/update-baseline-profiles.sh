#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 || ! -f "$1" ]]; then
  echo "usage: $0 <generated-baseline-prof.txt>" >&2
  exit 2
fi

repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
profile="$1"
if [[ "$profile" != /* ]]; then
  profile="$(pwd)/$profile"
fi

engine="$repo_root/engine/src/androidMain/baselineProfiles/baseline-prof.txt"
shaping="$repo_root/platforms/android/shaping/src/main/baselineProfiles/baseline-prof.txt"
rendering="$repo_root/platforms/android/rendering/src/main/baselineProfiles/baseline-prof.txt"
view="$repo_root/platforms/android/view/src/main/baselineProfiles/baseline-prof.txt"
compose="$repo_root/platforms/compose/compose/src/androidMain/baselineProfiles/baseline-prof.txt"

mkdir -p "$(dirname "$engine")" "$(dirname "$shaping")" "$(dirname "$rendering")" \
  "$(dirname "$view")" "$(dirname "$compose")"

staging_dir="$(mktemp -d "$repo_root/.baseline-profiles.XXXXXX")"
trap 'rm -rf "$staging_dir"' EXIT

staged_engine="$staging_dir/engine.txt"
staged_shaping="$staging_dir/shaping.txt"
staged_rendering="$staging_dir/rendering.txt"
staged_view="$staging_dir/view.txt"
staged_compose="$staging_dir/compose.txt"

# awk opens a redirected file only after the first matching print. Create every output up front so
# a category with no new entries remains empty instead of accidentally retaining an older profile.
: > "$staged_engine"
: > "$staged_shaping"
: > "$staged_rendering"
: > "$staged_view"
: > "$staged_compose"

awk '
  {
    owner = $0
    sub(/^[HSP]*/, "", owner)
    if (owner ~ /^Lorg\/tiqian\/android\/rendering\//) print > rendering
    else if (owner ~ /^Lorg\/tiqian\/android\/view\//) print > view
    else if (owner ~ /^Lorg\/tiqian\/shaping\/android\//) print > shaping
    else if (owner ~ /^Lorg\/tiqian\/compose\//) print > compose
    else if (owner ~ /^Lorg\/tiqian\/(core|layout|clreq|font|linebreak|shaping)\//) print > engine
  }
' engine="$staged_engine" shaping="$staged_shaping" rendering="$staged_rendering" \
  view="$staged_view" compose="$staged_compose" "$profile"

validate_profile() {
  local staged="$1"
  local destination="$2"
  test -s "$staged" || { echo "empty profile: $destination" >&2; exit 1; }
  LC_ALL=C sort -u -o "$staged" "$staged"
}

validate_profile "$staged_engine" "$engine"
validate_profile "$staged_shaping" "$shaping"
validate_profile "$staged_rendering" "$rendering"
validate_profile "$staged_view" "$view"
validate_profile "$staged_compose" "$compose"

mv -f "$staged_engine" "$engine"
mv -f "$staged_shaping" "$shaping"
mv -f "$staged_rendering" "$rendering"
mv -f "$staged_view" "$view"
mv -f "$staged_compose" "$compose"

echo "updated consumer profiles from $profile"
wc -l "$engine" "$shaping" "$rendering" "$view" "$compose"
