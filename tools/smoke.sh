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
git tag -a v1.0.0 -m "first release notes"
printf 'two\\n' >> app.txt && git add . && git commit -qm two
git tag v1.1.0
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

# useMostRecentTag：最近的标签名 + 消息（lightweight 没有正文，回落到提交说明）
dir="$(mktemp -d)"
printf '{"url": "%s", "branch": "main", "useMostRecentTag": "true"}' "$origin" >"$dir/input.json"
run "$dir" >"$dir/log" 2>&1 || fail "tag export: $(cat "$dir/output.json")"
[ "$(output "$dir" GIT_TAG_NAME)" = "v1.1.0" ] || fail "tag name: $(cat "$dir/output.json") log=$(tail -3 "$dir/log" 2>/dev/null | tr '\n' ' ') tags=$(git -C "$dir" tag | tr '\n' ' ')"
[ "$(output "$dir" GIT_TAG_MESSAGE)" = "two" ] || fail "lightweight tag must fall back to the commit subject"

# 附注标签：消息是标签正文，不是提交说明
dir="$(mktemp -d)"
printf '{"url": "%s", "commit": "v1.0.0", "useMostRecentTag": "true"}' "$origin" >"$dir/input.json"
run "$dir" >/dev/null 2>&1 || fail "annotated tag export: $(cat "$dir/output.json")"
[ "$(output "$dir" GIT_TAG_NAME)" = "v1.0.0" ] || fail "annotated tag name: $(cat "$dir/output.json")"
[ "$(output "$dir" GIT_TAG_MESSAGE)" = "first release notes" ] || fail "annotated tag message: $(cat "$dir/output.json")"

# 没开这个开关就不该冒出标签出参（避免下一步误用上一步的残留）
dir="$(mktemp -d)"
printf '{"url": "%s", "branch": "main"}' "$origin" >"$dir/input.json"
run "$dir" >/dev/null 2>&1 || fail "plain clone: $(cat "$dir/output.json")"
grep -q "GIT_TAG_NAME" "$dir/output.json" && fail "tag outputs appear without useMostRecentTag"

echo "smoke ok: clone, update, branch, targetDir, bad url, escape, most recent tag (annotated + lightweight + opt-out)"
