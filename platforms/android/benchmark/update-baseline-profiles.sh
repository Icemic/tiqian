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
' engine="$engine" shaping="$shaping" rendering="$rendering" view="$view" compose="$compose" "$profile"

for output in "$engine" "$shaping" "$rendering" "$view" "$compose"; do
  test -s "$output" || { echo "empty profile: $output" >&2; exit 1; }
  LC_ALL=C sort -u -o "$output" "$output"
done

echo "updated consumer profiles from $profile"
wc -l "$engine" "$shaping" "$rendering" "$view" "$compose"
