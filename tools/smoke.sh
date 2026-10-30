#!/usr/bin/env bash
# A real local repository: clone, update, switch branch, a missing url, and a bad directory.
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

origin="$(mktemp -d)/repo"
mkdir -p "$origin" && cd "$origin"
git init -q -b main . && git config user.email t@t && git config user.name t
printf 'one\n' > app.txt && git add . && git commit -qm one
git checkout -q -b feature && printf 'feature\n' > feature.txt && git add . && git commit -qm feature
git checkout -q main
cd - >/dev/null

# clone
dir="$(mktemp -d)"
printf '{"url": "%s", "branch": "main"}' "$origin" >"$dir/input.json"
run "$dir" >/dev/null 2>&1 || fail "clone: $(cat "$dir/output.json")"
[ -f "$dir/app.txt" ] || fail "no working tree"
[ "$(output "$dir" GIT_BRANCH)" = "main" ] || fail "branch: $(cat "$dir/output.json")"
first_commit="$(output "$dir" GIT_COMMIT)"
[ -n "$first_commit" ] || fail "no GIT_COMMIT"

# second run updates the existing clone instead of failing
printf 'two\n' >>"$origin/app.txt" && ( cd "$origin" && git commit -qam two )
printf '{"url": "%s", "branch": "main"}' "$origin" >"$dir/input.json"
run "$dir" >/dev/null 2>&1 || fail "update: $(cat "$dir/output.json")"
[ "$(output "$dir" GIT_COMMIT)" != "$first_commit" ] || fail "the update did not move HEAD"

# a different branch
printf '{"url": "%s", "branch": "feature"}' "$origin" >"$dir/input.json"
run "$dir" >/dev/null 2>&1 || fail "branch switch: $(cat "$dir/output.json")"
[ -f "$dir/feature.txt" ] || fail "feature branch content missing"

# a subdirectory target
dir="$(mktemp -d)"
printf '{"url": "%s", "branch": "main", "targetDir": "src"}' "$origin" >"$dir/input.json"
run "$dir" >/dev/null 2>&1 || fail "targetDir: $(cat "$dir/output.json")"
[ -f "$dir/src/app.txt" ] || fail "checkout did not land in src/"

# bad inputs
printf '{}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 && fail "no url must fail"
grep -q "url is required" "$dir/output.json" || fail "message: $(cat "$dir/output.json")"

printf '{"url": "%s", "targetDir": "../escape"}' "$origin" >"$dir/input.json"
run "$dir" >/dev/null 2>&1 && fail "escaping the workspace must fail"
grep -q "inside the workspace" "$dir/output.json" || fail "message: $(cat "$dir/output.json")"

echo "smoke ok: clone, update, branch, targetDir, bad url, escape"
