#!/bin/sh
# Publishes the files in directory $1 to the `dist` branch of `origin`, on top of whatever is already there
# (so a missing file keeps its previous version). The branch is always a single commit: no history to grow.
set -eu
src="$(cd "$1" && pwd)"
repo="$(pwd)"
branch="${DIST_BRANCH:-dist}"
work="$(mktemp -d)"
tmp="$branch-publish-$$"
cleanup() {
  git -C "$repo" worktree remove --force "$work" >/dev/null 2>&1 || true
  rm -rf "$work"
  git -C "$repo" branch -D "$tmp" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# Does the branch exist on the remote? ls-remote exits 2 when it does not; anything else is a real error
# (network, auth) and must stop us rather than silently publishing onto an empty tree.
rc=0
git ls-remote --exit-code --heads origin "$branch" >/dev/null 2>&1 || rc=$?
case "$rc" in
  0)
    # Leading "+": dist is force-pushed as a fresh orphan commit each time, so a stale local ref must be overwritten.
    git fetch --depth 1 origin "+$branch:refs/remotes/origin/$branch"
    git worktree add -q --detach "$work" "origin/$branch"
    ;;
  2)
    git worktree add -q --detach "$work" HEAD
    git -C "$work" rm -rfq . 2>/dev/null || true
    ;;
  *)
    echo "Cannot reach origin (ls-remote exit $rc); not publishing" >&2
    exit 1
    ;;
esac
cp -R "$src"/. "$work"/
cd "$work"
git add -A
if git diff --cached --quiet HEAD 2>/dev/null && git rev-parse -q --verify "origin/$branch" >/dev/null 2>&1 \
   && [ "$(git -C "$repo" rev-parse -q "origin/$branch^{tree}" 2>/dev/null)" = "$(git write-tree)" ]; then
  echo "Nothing to publish"
  exit 0
fi
git checkout -q --orphan "$tmp"
git -c user.name="${GIT_USER_NAME:-github-actions[bot]}" -c user.email="${GIT_USER_EMAIL:-41898282+github-actions[bot]@users.noreply.github.com}" \
  commit -q -m "Publish data $(date -u +%Y-%m-%dT%H:%MZ)"
git push -q --force origin "$tmp:$branch"
echo "Published to $branch"
