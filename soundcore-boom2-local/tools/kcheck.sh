#!/bin/bash
#
# The only local check of the Android layer that is worth anything.
#
# The Android and Compose jars are not reachable from this machine, so a compile
# here cannot succeed: every reference to them is reported as unresolved, hundreds
# of times. Filtering those by keyword is what let a missing `import CommandKind`
# reach CI - the name was an old one, so it fell through a filter looking for new
# ones.
#
# So this does not filter. It records the whole error set, normalised so that line
# numbers (which move on every edit) do not matter, and diffs it against a baseline
# taken from a commit CI proved green. Anything that appears is new, whatever it is
# called.
#
# How to read what it prints. An entry naming something of this project's own - a
# class, a function, a property - is a real error: a typo or a missing import, and
# CI will fail on it. An entry naming an Android or Compose symbol the baseline had
# never seen, because the change is the first to use it, is unavoidable noise; check
# by eye that the symbol exists and is imported, then refresh the baseline once CI
# is green again. The check narrows hundreds of lines to a handful; it does not
# replace CI, which is the only place this code really compiles.
#
#   tools/kcheck.sh baseline <git-ref>   # record the error set of a green commit
#   tools/kcheck.sh check                # list errors the working tree added
#
set -uo pipefail

here=$(cd "$(dirname "$0")/.." && pwd)
repo=$(cd "$here/.." && pwd)
work="${TMPDIR:-/tmp}/kcheck"
baseline="$here/tools/kcheck-baseline.txt"
mkdir -p "$work"

jars() {
  local std kj trove co ser
  std=$(find /root/.gradle/caches/modules-2 -name "kotlin-stdlib-2.1.0.jar" 2>/dev/null | head -1)
  kj=$(find /root/.gradle/caches/modules-2 -name "kotlin-compiler-embeddable-2.1.0.jar" 2>/dev/null | head -1)
  trove=$(find /root/.gradle -name "trove4j*.jar" 2>/dev/null | head -1)
  co=$(find /root/.gradle/caches/modules-2 -name "kotlinx-coroutines-core-jvm-1.*.jar" 2>/dev/null | sort | tail -1)
  ser=$(find /root/.gradle/caches/modules-2 \
        \( -name "kotlinx-serialization-json-jvm-*.jar" -o -name "kotlinx-serialization-core-jvm-*.jar" \) \
        2>/dev/null | sort -u | paste -sd:)
  if [ -z "$std" ] || [ -z "$kj" ]; then
    echo "kcheck: no Kotlin compiler in the Gradle cache; run the core tests once first" >&2
    exit 2
  fi
  printf '%s\n%s\n%s\n' "$kj:$std:$co:$trove" "$std:$co:$ser" ""
}

# Compiles one source root and writes its normalised error set.
errors_of() {
  local root="$1" out="$2" cp
  mapfile -t cp < <(jars)
  # Run from a fixed directory so the compiler's paths are predictable, and strip
  # any leading directory: the same file must normalise to the same key whether it
  # came from the working tree or from a worktree of an old commit.
  ( cd "$work" && java -cp "${cp[0]}" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
      -nowarn -no-stdlib -d "$work/out-$$" -classpath "${cp[1]}" \
      $(find "$root" -name "*.kt") 2>&1 ) \
    | grep "error:" \
    | sed -E 's#^.*(app/src/[^:]+):[0-9]+:[0-9]+: error: #\1 | #' \
    | sort -u > "$out"
  rm -rf "$work/out-$$"
}

case "${1:-check}" in
  baseline)
    ref="${2:?usage: kcheck.sh baseline <git-ref>}"
    tree="$work/tree-$ref"
    rm -rf "$tree"
    git -C "$repo" worktree add -q --detach "$tree" "$ref"
    errors_of "$tree/$(basename "$here")/app/src/main/kotlin" "$baseline"
    git -C "$repo" worktree remove --force "$tree"
    echo "baseline from $ref: $(wc -l < "$baseline") errors -> $baseline"
    ;;
  check)
    [ -f "$baseline" ] || { echo "kcheck: no baseline; run: tools/kcheck.sh baseline <green-ref>" >&2; exit 2; }
    errors_of "$here/app/src/main/kotlin" "$work/now.txt"
    added=$(comm -13 "$baseline" "$work/now.txt")
    if [ -z "$added" ]; then
      echo "kcheck: no errors beyond the baseline ($(wc -l < "$work/now.txt") total, $(wc -l < "$baseline") expected)"
    else
      echo "kcheck: errors the working tree added:"
      echo "$added"
      exit 1
    fi
    ;;
  *)
    echo "usage: tools/kcheck.sh [check | baseline <git-ref>]" >&2
    exit 2
    ;;
esac
