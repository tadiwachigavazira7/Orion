#!/usr/bin/env bash
# Stop hook: run the test suites for whichever half of the repo has uncommitted
# changes. Skips a suite when its changes are identical to the last run, so an
# unchanged working tree doesn't re-run Gradle/pytest on every turn.
# Never blocks: results are reported to the user via a systemMessage.

cd "$CLAUDE_PROJECT_DIR" 2>/dev/null || cd "$(dirname "$0")/../.." || exit 0

state_dir=".claude/.stop-checks"
mkdir -p "$state_dir"
messages=()

# Hash of the uncommitted diff + untracked files under a path; empty if clean.
changes_hash() {
  local path="$1"
  if [ -z "$(git status --porcelain -- "$path")" ]; then
    return
  fi
  { git diff HEAD -- "$path"; git ls-files --others --exclude-standard -- "$path" | xargs -r -d '\n' cat 2>/dev/null; } \
    | git hash-object --stdin
}

# run_suite <name> <path> <command...>
run_suite() {
  local name="$1" path="$2"; shift 2
  local hash; hash="$(changes_hash "$path")"
  [ -z "$hash" ] && return
  [ "$(cat "$state_dir/$name" 2>/dev/null)" = "$hash" ] && return

  local log="$state_dir/$name.log"
  if "$@" >"$log" 2>&1; then
    messages+=("$name: passed")
    echo "$hash" >"$state_dir/$name"
  else
    messages+=("$name: FAILED (see $log)")
    rm -f "$state_dir/$name"
  fi
}

run_suite "android-tests" "app" ./gradlew --quiet test
run_suite "backend-tests" "orion_fastAPI" bash -c 'cd orion_fastAPI && uv run pytest -q'

if [ ${#messages[@]} -gt 0 ]; then
  summary="$(printf '%s; ' "${messages[@]}")"
  printf '{"systemMessage": "Tests - %s"}\n' "${summary%; }"
fi
exit 0
